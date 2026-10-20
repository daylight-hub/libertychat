package network.libertychat.app.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.libertychat.app.data.db.entity.MessageEntity
import network.libertychat.app.data.repository.ConversationRepository
import network.libertychat.app.rns.api.RnsCore
import network.libertychat.app.rns.api.RnsLxmf
import network.libertychat.app.rns.api.model.DeliveryStatus
import network.libertychat.app.rns.api.model.DeliveryStatusUpdate
import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LCS: application-level service that applies LXMF delivery status updates and
 * asks for a fresh route when a send fails.
 *
 * ## Why this is not in MessagingViewModel any more
 *
 * It used to be, and that was the bug. `MessagingViewModel` is obtained with
 * `hiltViewModel()` inside the chat composable, so it is scoped to that
 * navigation entry and cleared when the user leaves the conversation. Delivery
 * failure is not instant — LXMF exhausts its own delivery attempts first, and
 * with a propagation node configured the fallback runs before anything terminal
 * is reported — so by the time a failure arrives the user has very often
 * navigated away. With the only collector on a dead `viewModelScope`, two things
 * silently did not happen: the message's status was never written, so it sat on
 * "sending" forever, and the automatic path request never fired at all.
 *
 * That is the same reasoning that moved message collection to
 * [MessageCollector] — its own documentation says it "solves the issue where
 * data was only collected when specific screens were active". Delivery status
 * was simply left behind.
 *
 * Nothing here touches UI state. The chat screen observes its messages from Room,
 * so writing the status from an application-scoped collector updates the UI
 * exactly as before, whether or not the conversation happens to be open.
 */
@Singleton
class DeliveryStatusCollector
    @Inject
    constructor(
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        private val conversationRepository: ConversationRepository,
        private val conversationLinkManager: ConversationLinkManager,
        private val identityResolutionManager: IdentityResolutionManager,
    ) {
        companion object {
            private const val TAG = "DeliveryStatusCollector"

            /**
             * A delivery proof can arrive before the database transaction that
             * stored the outgoing message has committed, so the row is looked up a
             * few times before being treated as unknown.
             */
            private val ROW_LOOKUP_RETRY_DELAYS_MS = listOf(50L, 100L, 200L)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Seam for tests, so path requests run on the test scheduler. */
        @VisibleForTesting
        internal var pathRequestDispatcher: CoroutineDispatcher = Dispatchers.IO

        @Volatile
        private var isStarted = false

        /**
         * Tracked so [stopCollecting] can actually end the subscription. Without
         * it, a stop followed by a start would leave two collectors running and
         * every status update would be applied twice.
         */
        private var collectJob: Job? = null

        fun startCollecting() {
            if (isStarted) {
                Log.w(TAG, "Delivery status collection already started")
                return
            }
            isStarted = true
            Log.i(TAG, "Starting delivery status collection")

            collectJob = scope.launch {
                try {
                    rnsLxmf.observeDeliveryStatus().collect { update ->
                        try {
                            Log.d(
                                TAG,
                                "Delivery status update: ${update.messageHash.take(16)}... -> ${update.status}",
                            )
                            handleDeliveryStatusUpdate(update)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // One bad update must not end the subscription; the next
                            // status for this or any other message still matters.
                            Log.e(TAG, "Error handling delivery status update", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting delivery status updates", e)
                }
            }
        }

        fun stopCollecting() {
            if (!isStarted) return
            isStarted = false
            collectJob?.cancel()
            collectJob = null
            Log.i(TAG, "Stopped delivery status collection")
        }

        @VisibleForTesting
        internal suspend fun handleDeliveryStatusUpdate(update: DeliveryStatusUpdate) {
            val originatingIdentityHash = update.originatingIdentityHash?.takeIf { it.isNotBlank() }
            if (originatingIdentityHash == null) {
                Log.w(TAG, "Ignoring delivery status without trustworthy attempt identity")
                return
            }

            val message = applyStatusWithRetries(update, originatingIdentityHash)
            if (message == null) {
                Log.w(
                    TAG,
                    "Delivery status update for unknown message after " +
                        "${ROW_LOOKUP_RETRY_DELAYS_MS.size} retries: ${update.messageHash.take(16)}...",
                )
                return
            }

            // A delivery proof is evidence the peer was online and heard us.
            if (update.status == DeliveryStatus.DELIVERED) {
                conversationLinkManager.recordPeerActivity(message.conversationHash, update.timestamp)
            }

            // Enrich sentInterface on a terminal-success state — the routing table
            // still reflects the actual send path. For DIRECT/OPPORTUNISTIC this
            // fires on "delivered"; for PROPAGATED on "propagated" (the
            // propagation-node-accepted analogue). Failed and retrying paths carry
            // too much routing ambiguity to produce accurate interface data.
            if (update.status == DeliveryStatus.DELIVERED || update.status == DeliveryStatus.PROPAGATED) {
                enrichSentInterfaceOnDelivery(message)
            }

            if (directRouteFailed(update, message)) {
                requestPathAfterDeliveryFailure(message.conversationHash)
            }
            if (propagationRouteFailed(update, message)) {
                requestPathToPropagationNode()
            }

            Log.d(TAG, "Updated message ${update.messageHash.take(16)}... status to ${update.status.wireValue}")
        }

        private suspend fun applyStatusWithRetries(
            update: DeliveryStatusUpdate,
            originatingIdentityHash: String,
        ): MessageEntity? {
            var message =
                conversationRepository.applyDeliveryStatus(
                    update.messageHash,
                    update.status.wireValue,
                    originatingIdentityHash,
                )
            for (delayMs in ROW_LOOKUP_RETRY_DELAYS_MS) {
                if (message != null) break
                Log.d(
                    TAG,
                    "Message ${update.messageHash.take(16)}... not found, retrying in ${delayMs}ms",
                )
                delay(delayMs)
                message =
                    conversationRepository.applyDeliveryStatus(
                        update.messageHash,
                        update.status.wireValue,
                        originatingIdentityHash,
                    )
            }
            return message
        }

        /**
         * LCS: whether the *direct* route is the one that just failed, meaning the
         * peer is who we should be asking about.
         *
         * `RETRYING_PROPAGATED` has to be in here, and is in fact the more
         * important of the two. When a propagation node is configured and
         * `try_propagation_on_fail` is set — the default — a failed direct delivery
         * does NOT report `FAILED`. `event_bridge.py` schedules the propagation
         * fallback instead and emits `RETRYING_PROPAGATED`, and `FAILED` only
         * arrives if that fallback also fails. So for most users the direct route
         * going stale would never reach this hook on `FAILED` alone.
         *
         * That branch must NOT be gated on `deliveryMethod`: the reducer in
         * `MessageDao` rewrites it to "propagated" as part of this very update, so
         * the row read back here always says "propagated" — testing it would
         * suppress the exact case the feature exists for.
         */
        @VisibleForTesting
        internal fun directRouteFailed(
            update: DeliveryStatusUpdate,
            message: MessageEntity,
        ): Boolean =
            when (update.status) {
                DeliveryStatus.RETRYING_PROPAGATED -> true
                DeliveryStatus.FAILED -> message.deliveryMethod != "propagated"
                else -> false
            }

        /**
         * LCS: the last leg. A propagated message that failed means the propagation
         * node was unreachable; asking for a path to the peer would be pointless
         * because the peer was never the next hop.
         */
        @VisibleForTesting
        internal fun propagationRouteFailed(
            update: DeliveryStatusUpdate,
            message: MessageEntity,
        ): Boolean = update.status == DeliveryStatus.FAILED && message.deliveryMethod == "propagated"

        private suspend fun enrichSentInterfaceOnDelivery(message: MessageEntity) {
            if (!message.isFromMe || message.sentInterface != null) return
            try {
                val lookupHash =
                    if (message.deliveryMethod == "propagated") {
                        runCatching { rnsLxmf.getOutboundPropagationNode().getOrNull() }
                            .getOrNull()
                            ?.let { hexToBytesOrNull(it) }
                            ?: return // no propagation node configured → nothing useful to query
                    } else {
                        hexToBytesOrNull(message.conversationHash) ?: return
                    }
                val sentInterface = rnsCore.getNextHopInterfaceName(lookupHash)
                if (sentInterface != null) {
                    conversationRepository.updateMessageSentInterface(
                        message.id,
                        sentInterface,
                        message.identityHash,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enrich sent interface on delivery: ${e.message}")
            }
        }

        /**
         * LCS: ask the network for a fresh route after a message we had already
         * handed to the backend came back failed.
         *
         * This is the hook that earns its keep, and the reason it is here rather
         * than on the synchronous send failure. A send that fails *synchronously*
         * does so only when the backend couldn't resolve the recipient's identity
         * at all, and by then it has already called `request_path` itself and
         * polled for ten seconds — asking again would just put another broadcast on
         * the air for nothing.
         *
         * The case with no path request anywhere is this one. Once a peer has been
         * resolved its destination is cached, so a peer that was reachable an hour
         * ago and isn't now goes straight to `handle_outbound` over a path that no
         * longer leads anywhere, and surfaces as a delivery failure later.
         *
         * Nothing re-sends automatically: a route takes time to come back and a
         * retry racing the announce would fail again. The message is already marked
         * failed, so Retry is there once the path returns. Rate-limiting lives in
         * [IdentityResolutionManager.forcePathRequest] — a conversation failing
         * repeatedly costs one request per minute, not one per message.
         */
        private fun requestPathAfterDeliveryFailure(destinationHash: String) {
            if (destinationHash.isBlank()) return
            scope.launch(pathRequestDispatcher) {
                runCatching { identityResolutionManager.forcePathRequest(destinationHash) }
                    .onFailure { e -> Log.e(TAG, "Path request after delivery failure threw", e) }
            }
        }

        /**
         * LCS: ask for a route to the configured outbound propagation node, after a
         * propagated message failed to reach it.
         *
         * Keyed on the node's own hash, so the per-destination cooldown in
         * [IdentityResolutionManager.forcePathRequest] rate-limits node requests
         * separately from peer requests rather than one starving the other.
         */
        private fun requestPathToPropagationNode() {
            scope.launch(pathRequestDispatcher) {
                val nodeHash =
                    runCatching { rnsLxmf.getOutboundPropagationNode().getOrNull() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }

                if (nodeHash == null) {
                    Log.d(TAG, "Propagated delivery failed with no propagation node configured")
                    return@launch
                }

                runCatching { identityResolutionManager.forcePathRequest(nodeHash) }
                    .onFailure { e -> Log.e(TAG, "Path request for propagation node threw", e) }
            }
        }

        private fun hexToBytesOrNull(hex: String): ByteArray? =
            runCatching {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull()
    }
