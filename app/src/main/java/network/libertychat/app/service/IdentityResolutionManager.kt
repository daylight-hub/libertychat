package network.libertychat.app.service

import android.util.Log
import network.libertychat.app.data.db.entity.ContactStatus
import network.libertychat.app.data.repository.ContactRepository
import network.libertychat.app.data.repository.ConversationRepository
import network.libertychat.app.rns.api.RnsCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages background identity resolution for pending contacts.
 *
 * This manager:
 * 1. Requests paths for the 3 most recent conversations at startup
 * 2. Periodically checks pending contacts (every 3h, gives up after 24h)
 * 3. Persists transport data for crash resilience
 *
 * Automatic path requests go through [requestPathIfNeeded], which checks hasPath
 * first. The one exception is [forcePathRequest], which deliberately ignores an
 * existing path — see its KDoc for why a send failure needs that.
 */
@Singleton
class IdentityResolutionManager
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val conversationRepository: ConversationRepository,
        private val rnsCore: RnsCore,
    ) {
        companion object {
            private const val TAG = "IdentityResolutionMgr"

            // Check interval: 3 hours
            private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L

            // Resolution timeout: 24 hours
            private const val RESOLUTION_TIMEOUT_MS = 24 * 60 * 60 * 1000L

            // Stagger path requests to avoid flooding the network
            private const val PATH_REQUEST_STAGGER_MS = 2_000L

            // Delay before startup sweep to let Reticulum initialize
            private const val STARTUP_SWEEP_DELAY_MS = 5_000L

            // Number of recent conversations to request paths for at startup
            private const val STARTUP_SWEEP_LIMIT = 3

            /**
             * LCS: minimum gap between automatic forced path requests to the same
             * destination. See [forcePathRequest]. Long enough that a failing queue
             * can't flood a LoRa link, short enough that a user who waits out one
             * failure and tries again gets a fresh request.
             */
            private const val FORCED_REQUEST_COOLDOWN_MS = 60_000L
        }

        private var resolutionJob: Job? = null
        private var startupSweepJob: Job? = null

        /**
         * LCS: destination hash -> last forced path request, for the cooldown in
         * [forcePathRequest]. Concurrent because failure callbacks and user taps
         * arrive on different coroutines; bounded in practice by the number of
         * conversations the user actually sends to.
         */
        private val lastForcedRequestAt = ConcurrentHashMap<String, Long>()

        /**
         * Start the periodic identity resolution checks.
         * Should be called after Reticulum is initialized.
         */
        fun start(scope: CoroutineScope) {
            if (resolutionJob?.isActive == true) {
                Log.d(TAG, "Resolution manager already running")
                return
            }

            Log.d(TAG, "Starting identity resolution manager")
            resolutionJob =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        try {
                            checkPendingContacts()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during identity resolution check", e)
                        }

                        delay(CHECK_INTERVAL_MS)
                    }
                }

            // One-shot startup sweep: request paths for 3 most recent conversations
            startupSweepJob =
                scope.launch(Dispatchers.IO) {
                    delay(STARTUP_SWEEP_DELAY_MS)
                    requestPathsForRecentConversations()
                }
        }

        /**
         * Stop the periodic identity resolution checks.
         */
        fun stop() {
            Log.d(TAG, "Stopping identity resolution manager")
            resolutionJob?.cancel()
            resolutionJob = null
            startupSweepJob?.cancel()
            startupSweepJob = null
        }

        /**
         * Check all pending contacts and attempt to resolve their identities.
         */
        private suspend fun checkPendingContacts() {
            val pendingContacts =
                contactRepository.getContactsByStatus(listOf(ContactStatus.PENDING_IDENTITY))

            if (pendingContacts.isEmpty()) {
                Log.d(TAG, "No pending contacts to resolve")
            } else {
                Log.d(TAG, "Checking ${pendingContacts.size} pending contact(s)")

                val currentTime = System.currentTimeMillis()

                for (contact in pendingContacts) {
                    try {
                        // Check if resolution has timed out (24 hours)
                        val age = currentTime - contact.addedTimestamp
                        if (age > RESOLUTION_TIMEOUT_MS) {
                            Log.d(TAG, "Contact ${contact.destinationHash.take(8)}... timed out after 24h")
                            contactRepository.updateContactStatus(
                                destinationHash = contact.destinationHash,
                                status = ContactStatus.UNRESOLVED,
                            )
                            continue
                        }

                        // Try to recall identity from Reticulum's cache
                        val destHashBytes =
                            contact.destinationHash
                                .chunked(2)
                                .map { it.toInt(16).toByte() }
                                .toByteArray()

                        val identity = rnsCore.recallIdentity(destHashBytes)

                        if (identity != null && identity.publicKey != null) {
                            // Identity found! Update the contact
                            Log.i(TAG, "Resolved identity for ${contact.destinationHash.take(8)}...")
                            contactRepository.updateContactWithIdentity(
                                destinationHash = contact.destinationHash,
                                publicKey = identity.publicKey,
                            )
                        } else {
                            // Not in cache, request path (guarded) to trigger network search
                            requestPathIfNeeded(destHashBytes, contact.destinationHash)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing contact ${contact.destinationHash.take(8)}...", e)
                    }
                }
            }

            // Periodically persist transport data (paths, destinations) to survive process kills
            try {
                rnsCore.persistTransportData()
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting transport data", e)
            }
        }

        /**
         * Request a path for a single contact if one doesn't already exist.
         * Used when adding a new contact or opening a conversation.
         */
        suspend fun requestPathForContact(destinationHash: String) {
            try {
                val destHashBytes =
                    destinationHash
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                requestPathIfNeeded(destHashBytes, destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting path for ${destinationHash.take(8)}...", e)
            }
        }

        /**
         * Request paths for the N most recent conversations.
         * Called once at startup to ensure the most relevant peers are reachable.
         */
        private suspend fun requestPathsForRecentConversations() {
            try {
                val recentPeerHashes = conversationRepository.getRecentPeerHashes(STARTUP_SWEEP_LIMIT)

                if (recentPeerHashes.isEmpty()) {
                    Log.d(TAG, "Startup sweep: no recent conversations")
                    return
                }

                Log.d(TAG, "Startup sweep: requesting paths for ${recentPeerHashes.size} recent conversation(s)")

                for (peerHash in recentPeerHashes) {
                    try {
                        val destHashBytes =
                            peerHash
                                .chunked(2)
                                .map { it.toInt(16).toByte() }
                                .toByteArray()

                        requestPathIfNeeded(destHashBytes, peerHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "Startup sweep: error for ${peerHash.take(8)}...", e)
                    }
                    delay(PATH_REQUEST_STAGGER_MS)
                }

                Log.d(TAG, "Startup sweep complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error during startup path sweep", e)
            }
        }

        /**
         * Manually trigger a resolution check for a specific contact.
         * Used when user taps "retry" on an unresolved contact.
         *
         * Note: The caller (ContactsViewModel.retryIdentityResolution) is responsible
         * for calling contactRepository.resetContactForRetry() before invoking this.
         */
        suspend fun retryResolution(destinationHash: String) {
            Log.d(TAG, "Retry resolution for ${destinationHash.take(8)}...")

            try {
                val destHashBytes =
                    destinationHash
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                requestPathIfNeeded(destHashBytes, destinationHash)
            } catch (e: Exception) {
                Log.e(TAG, "Error in retryResolution for ${destinationHash.take(8)}...", e)
            }
        }

        /**
         * Central path request method — checks hasPath before requesting.
         * All path requests in this class must go through here.
         */
        private suspend fun requestPathIfNeeded(
            destHashBytes: ByteArray,
            displayHash: String,
        ) {
            if (rnsCore.hasPath(destHashBytes)) {
                Log.d(TAG, "Path exists for ${displayHash.take(8)}..., skipping request")
                return
            }

            Log.d(TAG, "Requesting path for ${displayHash.take(8)}...")
            rnsCore.requestPath(destHashBytes)
        }

        /**
         * LCS: issue a path request even when a path is already known.
         *
         * [requestPathIfNeeded]'s `hasPath` guard is right for the startup sweep and
         * for opening a chat — there is no point asking the network for a route we
         * already have. It is wrong after a send has failed. The common reason a
         * send fails to a peer we hold a path for is that the path is *stale*: the
         * next hop it names has gone away, and RNS will not notice until the entry
         * expires on its own. Honouring `hasPath` there would skip the one request
         * that could recover the conversation.
         *
         * A path request is broadcast on every active interface, so on a LoRa link
         * it costs real airtime. Automatic callers therefore pass
         * [respectCooldown] = true, which collapses a burst — a queue draining
         * against a downed interface, several failures in a row — into one request
         * per [FORCED_REQUEST_COOLDOWN_MS] per destination. A user tapping
         * "Request path" in the message menu passes false: they asked for it
         * explicitly and should not be silently ignored.
         *
         * @return true if a request was put on the air, false if the hash was
         *         malformed, the backend rejected it, or the cooldown suppressed it.
         */
        suspend fun forcePathRequest(
            destinationHash: String,
            respectCooldown: Boolean = true,
        ): Boolean {
            val destHashBytes = parseDestinationHash(destinationHash)
            if (destHashBytes == null) {
                Log.e(TAG, "Cannot request path for malformed hash ${destinationHash.take(8)}...")
                return false
            }

            val now = System.currentTimeMillis()
            if (respectCooldown) {
                val last = lastForcedRequestAt[destinationHash]
                if (last != null && now - last < FORCED_REQUEST_COOLDOWN_MS) {
                    Log.d(
                        TAG,
                        "Forced path request for ${destinationHash.take(8)}... suppressed " +
                            "(${(now - last) / 1000}s since last, cooldown ${FORCED_REQUEST_COOLDOWN_MS / 1000}s)",
                    )
                    return false
                }
            }

            // Recorded before the call, not after: the point is to rate-limit how
            // often we transmit, and a slow or failing backend call should not open
            // the gate for a retry storm behind it.
            lastForcedRequestAt[destinationHash] = now

            Log.d(TAG, "Forcing path request for ${destinationHash.take(8)}...")
            return rnsCore
                .requestPath(destHashBytes)
                .onFailure { e ->
                    Log.e(TAG, "Path request failed for ${destinationHash.take(8)}...", e)
                }.isSuccess
        }

        /**
         * LCS: hex string to bytes, or null if [destinationHash] isn't valid hex.
         *
         * The older methods in this class let [String.toInt] throw and catch
         * `Exception` around the whole body. New code returns null instead, so a
         * malformed hash is distinguishable from a backend failure.
         */
        private fun parseDestinationHash(destinationHash: String): ByteArray? =
            try {
                if (destinationHash.isBlank() || destinationHash.length % 2 != 0) {
                    null
                } else {
                    destinationHash
                        .chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Malformed destination hash ${destinationHash.take(8)}...", e)
                null
            }
    }
