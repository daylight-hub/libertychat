package network.libertychat.app.gateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import network.libertychat.app.rns.api.model.InterfaceConfig
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * where the live LCS Global host, port and passphrase are kept.
 *
 * Not in the interface database: that is included in Android cloud backups, device
 * transfers and Settings > Data Migration exports. Instead they live in
 * `noBackupFilesDir` (never backed up), encrypted with an AES key held in the
 * Android Keystore. The database row only holds a placeholder; [apply] swaps the
 * real values in whenever the app builds the Reticulum configuration.
 *
 * If this file is missing (new phone, restored backup, cleared data), the gateway
 * manager simply fetches a fresh session from the server.
 */
object GatewaySecrets {
    data class Session(
        val host: String,
        val port: Int,
        val networkName: String?,
        val passphrase: String?,
    )

    private const val TAG = "GatewaySecrets"
    private const val KEY_ALIAS = "lcs_global_session_key"
    private const val FILE_NAME = "lcs_global_session.bin"
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var file: File? = null

    @Volatile
    private var cached: Session? = null

    @Volatile
    private var loaded = false

    /** Called once at app start (main process), before any Reticulum config is built. */
    fun init(context: Context) {
        file = File(context.noBackupFilesDir, FILE_NAME)
    }

    @Synchronized
    fun get(): Session? {
        if (loaded) return cached
        val f = file ?: return null
        cached = runCatching { if (f.isFile) decrypt(f.readBytes()) else null }
            .onFailure {
                Log.w(TAG, "Stored session unreadable; a new one will be fetched", it)
                f.delete()
            }.getOrNull()
        loaded = true
        return cached
    }

    @Synchronized
    fun put(session: Session?) {
        val f = file ?: return
        if (session == null) {
            f.delete()
        } else {
            val json =
                JSONObject()
                    .put("host", session.host)
                    .put("port", session.port)
                    .put("network_name", session.networkName ?: JSONObject.NULL)
                    .put("passphrase", session.passphrase ?: JSONObject.NULL)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeBytes(encrypt(json.toString().toByteArray(Charsets.UTF_8)))
            tmp.renameTo(f)
        }
        cached = session
        loaded = true
    }

    /** The managed gateway interface with its real target filled in; anything else unchanged. */
    fun apply(
        config: InterfaceConfig,
        entityName: String,
    ): InterfaceConfig {
        if (config !is InterfaceConfig.TCPClient || !GatewayConfig.isGateway(entityName)) return config
        val s = get() ?: return config
        return config.copy(targetHost = s.host, targetPort = s.port, networkName = s.networkName, passphrase = s.passphrase)
    }

    // ---------------------------------------------------------------- Keystore AES-GCM

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): Session {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val json = JSONObject(String(cipher.doFinal(blob.copyOfRange(1 + ivLen, blob.size)), Charsets.UTF_8))
        return Session(
            host = json.getString("host"),
            port = json.getInt("port"),
            networkName = json.optStringOrNull("network_name"),
            passphrase = json.optStringOrNull("passphrase"),
        )
    }
}
