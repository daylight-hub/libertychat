package network.libertychat.app.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.libertychat.app.data.db.entity.LocalIdentityEntity
import network.libertychat.app.data.repository.IdentityRepository
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * signs entitlement requests with the active Reticulum identity.
 *
 * A Reticulum identity file is 64 bytes: X25519 private key (32) then Ed25519
 * signing seed (32). Its public key is X25519 public (32) + Ed25519 public (32),
 * exactly what the server hashes to get the identity hash and LXMF address. Only
 * the holder of the identity can produce these signatures, so a copied address
 * gets nothing from the server.
 */
@Singleton
class GatewaySigner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val identityRepository: IdentityRepository,
    ) {
        companion object {
            private const val TAG = "GatewaySigner"
            private const val KEY_LEN = 64
        }

        class Keys(
            val identityHash: String,
            val publicKey: ByteArray,
            internal val signingSeed: ByteArray,
        )

        class Signed(
            val body: ByteArray,
            val signature: String,
        )

        private val random = SecureRandom()

        /** Server clock minus ours, learned from a `clock_skew` reply. */
        @Volatile
        var clockOffsetSeconds: Long = 0

        suspend fun activeKeys(): Keys? = identityRepository.getActiveIdentitySync()?.let { keysFor(it) }

        /** Keys for any identity on this phone (used to sign consent when moving a subscription). */
        suspend fun keysFor(identity: LocalIdentityEntity): Keys? =
            withContext(Dispatchers.IO) {
                val raw = readKey(identity) ?: return@withContext null
                try {
                    val xPub = ByteArray(32)
                    X25519PrivateKeyParameters(raw, 0).generatePublicKey().encode(xPub, 0)
                    val edPub = ByteArray(32)
                    Ed25519PrivateKeyParameters(raw, 32).generatePublicKey().encode(edPub, 0)
                    (xPub + edPub).let { pub ->
                        // Same derivation as Reticulum and the server: first 16 bytes of SHA-256(public key).
                        val hash = MessageDigest.getInstance("SHA-256").digest(pub).copyOfRange(0, 16).toHex()
                        Keys(hash, pub, raw.copyOfRange(32, KEY_LEN))
                    }
                } finally {
                    raw.fill(0)
                }
            }

        private suspend fun readKey(identity: LocalIdentityEntity): ByteArray? {
            val candidates =
                listOf(
                    File(context.filesDir, "reticulum/identity_${identity.identityHash}"),
                    File(identity.filePath),
                )
            for (file in candidates) {
                try {
                    if (file.isFile && file.length() == KEY_LEN.toLong()) return file.readBytes()
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot read ${file.name}", e)
                }
            }
            // Password-protected identities may only exist encrypted at rest.
            return identityRepository
                .getDecryptedKeyData(identity.identityHash)
                .getOrNull()
                ?.takeIf { it.size == KEY_LEN }
        }

        /** SHA-256 of ANDROID_ID: stable across uninstall/reinstall, changes on factory reset. */
        @SuppressLint("HardwareIds")
        fun deviceHash(): String {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            if (id.isEmpty()) return ""
            return sha256Hex(GatewayConfig.DEVICE_SALT + id)
        }

        /**
         * Build and sign a request body. [extra] can add fields that depend on this request's
         * timestamp and nonce (the transfer consent signs over them).
         */
        fun sign(
            keys: Keys,
            action: String,
            extra: ((ts: Long, nonce: String) -> Map<String, String>)? = null,
        ): Signed {
            val nonce = ByteArray(16).also { random.nextBytes(it) }.toHex()
            val ts = System.currentTimeMillis() / 1000 + clockOffsetSeconds
            val json =
                JSONObject()
                    .put("v", 1)
                    .put("action", action)
                    .put("pub", Base64.encodeToString(keys.publicKey, Base64.NO_WRAP))
                    .put("dev", deviceHash())
                    .put("ts", ts)
                    .put("nonce", nonce)
            extra?.invoke(ts, nonce)?.forEach { (k, v) -> json.put(k, v) }
            val body = json.toString().toByteArray(Charsets.UTF_8)
            return Signed(body, Base64.encodeToString(ed25519(keys.signingSeed, body), Base64.NO_WRAP))
        }

        /**
         * The old identity's consent to hand its gateway access to [toIdentityHash]. Bound to
         * the request's own timestamp and nonce, so it can't be reused for another request.
         */
        fun transferConsent(
            from: Keys,
            toIdentityHash: String,
            ts: Long,
            nonce: String,
        ): Map<String, String> {
            val statement = "lcs-gw-transfer-v1:$toIdentityHash:$ts:$nonce".toByteArray(Charsets.UTF_8)
            return mapOf(
                "from_pub" to Base64.encodeToString(from.publicKey, Base64.NO_WRAP),
                "from_sig" to Base64.encodeToString(ed25519(from.signingSeed, statement), Base64.NO_WRAP),
            )
        }

        private fun ed25519(
            seed: ByteArray,
            message: ByteArray,
        ): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
