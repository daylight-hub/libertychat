package network.libertychat.app.service

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import network.libertychat.app.data.db.entity.MessageEntity
import network.libertychat.app.data.repository.ConversationRepository
import network.libertychat.app.rns.api.RnsCore
import network.libertychat.app.rns.api.RnsLxmf
import network.libertychat.app.rns.api.model.DeliveryStatus
import network.libertychat.app.rns.api.model.DeliveryStatusUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LCS: tests for [DeliveryStatusCollector].
 *
 * These moved here from `MessagingViewModelTest` along with the code. They used
 * to construct an entire `MessagingViewModel` purely to pick up its
 * `observeDeliveryStatus()` subscription — which was the bug: that subscription
 * lived on a `viewModelScope` belonging to the chat screen's navigation entry, so
 * a delivery failure arriving after the user left the conversation was never
 * applied and never triggered a path request.
 *
 * Driving [DeliveryStatusCollector.handleDeliveryStatusUpdate] directly also
 * makes the intent legible: every one of these is about what a given status does
 * to the database and to route discovery, and none of it was ever about the UI.
 */
class DeliveryStatusCollectorTest {
    private lateinit var rnsCore: RnsCore
    private lateinit var rnsLxmf: RnsLxmf
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationLinkManager: ConversationLinkManager
    private lateinit var identityResolutionManager: IdentityResolutionManager
    private lateinit var collector: DeliveryStatusCollector

    private val testPeerHash = "abcdef0123456789abcdef0123456789"
    private val identityHash = "test_identity_hash"

    @Before
    fun setup() {
        rnsCore = mockk()
        rnsLxmf = mockk()
        conversationRepository = mockk()
        conversationLinkManager = mockk()
        identityResolutionManager = mockk()

        every { conversationLinkManager.recordPeerActivity(any(), any()) } just Runs
        coEvery { identityResolutionManager.forcePathRequest(any(), any()) } returns true

        collector =
            DeliveryStatusCollector(
                rnsCore = rnsCore,
                rnsLxmf = rnsLxmf,
                conversationRepository = conversationRepository,
                conversationLinkManager = conversationLinkManager,
                identityResolutionManager = identityResolutionManager,
            )
    }

    /** Pins the collector's path-request launches to the test scheduler. */
    private fun runCollectorTest(block: suspend TestScope.() -> Unit) =
        runTest {
            collector.pathRequestDispatcher = StandardTestDispatcher(testScheduler)
            block()
        }

    private fun message(
        hash: String,
        status: String = "sent",
        deliveryMethod: String? = "direct",
        sentInterface: String? = "known",
    ) = MessageEntity(
        id = hash,
        conversationHash = testPeerHash,
        identityHash = identityHash,
        content = "Test message",
        timestamp = 1000L,
        isFromMe = true,
        status = status,
        deliveryMethod = deliveryMethod,
        sentInterface = sentInterface,
    )

    private fun update(
        hash: String,
        status: DeliveryStatus,
        originating: String? = identityHash,
    ) = DeliveryStatusUpdate(
        messageHash = hash,
        status = status,
        timestamp = 1_700_000_000_000L,
        originatingIdentityHash = originating,
    )

    private fun givenRow(
        hash: String,
        row: MessageEntity?,
    ) {
        coEvery { conversationRepository.applyDeliveryStatus(hash, any(), identityHash) } returns row
    }

    // ========== Status is handed to the repository reducer ==========

    /**
     * The reducer in `MessageDao` owns every status-precedence decision, including
     * rewriting `deliveryMethod`. The collector must hand the status over and not
     * second-guess it — hence the companion assertion that the separate
     * delivery-details writer is never called.
     */
    private fun assertDelegatesToReducer(
        emitted: DeliveryStatus,
        wire: String,
        existingStatus: String,
    ) = runCollectorTest {
        val hash = "hash_${wire}_$existingStatus"
        givenRow(hash, message(hash, status = existingStatus))

        collector.handleDeliveryStatusUpdate(update(hash, emitted))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.applyDeliveryStatus(hash, wire, identityHash)
        }
        coVerify(exactly = 0) {
            conversationRepository.updateMessageDeliveryDetails(any(), any(), any())
        }
    }

    @Test
    fun `retrying_propagated is handed to the reducer, which also rewrites deliveryMethod`() =
        assertDelegatesToReducer(DeliveryStatus.RETRYING_PROPAGATED, "retrying_propagated", "sent")

    @Test
    fun `delivered is handed to the reducer`() =
        assertDelegatesToReducer(DeliveryStatus.DELIVERED, "delivered", "sent")

    @Test
    fun `failed is handed to the reducer when the row is pending`() =
        assertDelegatesToReducer(DeliveryStatus.FAILED, "failed", "pending")

    @Test
    fun `failed is handed to the reducer when the row is already sent`() =
        assertDelegatesToReducer(DeliveryStatus.FAILED, "failed", "sent")

    @Test
    fun `failed is handed to the reducer when the row is already propagated`() =
        assertDelegatesToReducer(DeliveryStatus.FAILED, "failed", "propagated")

    @Test
    fun `failed is handed to the reducer when the row is already delivered`() =
        assertDelegatesToReducer(DeliveryStatus.FAILED, "failed", "delivered")

    @Test
    fun `propagated is handed to the reducer when the row is already delivered`() =
        assertDelegatesToReducer(DeliveryStatus.PROPAGATED, "propagated", "delivered")

    @Test
    fun `retrying_propagated is handed to the reducer when the row is already delivered`() =
        assertDelegatesToReducer(DeliveryStatus.RETRYING_PROPAGATED, "retrying_propagated", "delivered")

    @Test
    fun `pending is handed to the reducer when the row is already delivered`() =
        assertDelegatesToReducer(DeliveryStatus.PENDING, "pending", "delivered")

    @Test
    fun `an unknown message hash is given up on rather than retried forever`() =
        runCollectorTest {
            val hash = "unknown_hash"
            givenRow(hash, null)

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.DELIVERED))
            advanceUntilIdle()

            // Looked up, then abandoned: no peer activity, no path request.
            coVerify(exactly = 0) { identityResolutionManager.forcePathRequest(any(), any()) }
            coVerify(exactly = 0) { conversationLinkManager.recordPeerActivity(any(), any()) }
        }

    @Test
    fun `a status with no originating identity is ignored`() =
        runCollectorTest {
            // Without a trustworthy attempt identity the row cannot be addressed
            // safely — two identities can hold the same message hash.
            collector.handleDeliveryStatusUpdate(
                update("any_hash", DeliveryStatus.DELIVERED, originating = null),
            )
            advanceUntilIdle()

            coVerify(exactly = 0) {
                conversationRepository.applyDeliveryStatus(any(), any(), any())
            }
        }

    // ========== Sent-interface enrichment ==========

    @Test
    fun `enrichment writes against the attempt identity, not the active one`() =
        runCollectorTest {
            // A duplicate message hash can exist under two identities. The write has
            // to name the identity the delivery callback belonged to, or it lands on
            // the wrong row after an identity switch.
            val duplicateHash = "duplicate_hash"
            givenRow(
                duplicateHash,
                message(duplicateHash, status = "sent", deliveryMethod = "direct", sentInterface = null),
            )
            coEvery { rnsCore.getNextHopInterfaceName(any()) } returns "A Route"
            coEvery {
                conversationRepository.updateMessageSentInterface(any(), any(), any())
            } just Runs

            collector.handleDeliveryStatusUpdate(update(duplicateHash, DeliveryStatus.DELIVERED))
            advanceUntilIdle()

            coVerify(exactly = 1) {
                conversationRepository.updateMessageSentInterface(duplicateHash, "A Route", identityHash)
            }
        }

    @Test
    fun `enrichment is skipped when the interface is already recorded`() =
        runCollectorTest {
            val hash = "already_enriched"
            givenRow(hash, message(hash, sentInterface = "Existing"))

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.DELIVERED))
            advanceUntilIdle()

            coVerify(exactly = 0) { rnsCore.getNextHopInterfaceName(any()) }
        }

    // ========== Path request on failure ==========

    @Test
    fun `a failed delivery forces a path request for the peer`() =
        runCollectorTest {
            // The case the feature exists for: the message was accepted for sending,
            // so the backend never resolved an identity and never asked for a path.
            // The route it used has since died, and the failure arrives async.
            val hash = "failed_delivery_hash"
            givenRow(hash, message(hash))
            val requestedHash = slot<String>()
            val respectCooldown = slot<Boolean>()
            coEvery {
                identityResolutionManager.forcePathRequest(capture(requestedHash), capture(respectCooldown))
            } returns true

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.FAILED))
            advanceUntilIdle()

            assertEquals(
                "Should ask about the peer the failed message was addressed to",
                testPeerHash,
                requestedHash.captured,
            )
            assertTrue("An automatic request must respect the cooldown", respectCooldown.captured)
        }

    @Test
    fun `a propagation retry forces a path request for the peer`() =
        runCollectorTest {
            // The important one. With a propagation node configured — the default —
            // a failed direct delivery reports RETRYING_PROPAGATED, not FAILED, so
            // FAILED alone would miss the stale-route case for most users. The row
            // read back already says "propagated" because the reducer rewrote it as
            // part of this very update, which is why this must not be gated on it.
            val hash = "retrying_hash"
            givenRow(hash, message(hash, deliveryMethod = "propagated"))
            val requestedHash = slot<String>()
            coEvery {
                identityResolutionManager.forcePathRequest(capture(requestedHash), any())
            } returns true

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.RETRYING_PROPAGATED))
            advanceUntilIdle()

            assertEquals(testPeerHash, requestedHash.captured)
        }

    @Test
    fun `a failed propagated message asks about the node, not the peer`() =
        runCollectorTest {
            // The peer was never the next hop, so asking about them is pointless.
            val hash = "failed_propagated_hash"
            val nodeHash = "00112233445566770011223344556677"
            givenRow(hash, message(hash, status = "propagated", deliveryMethod = "propagated"))
            coEvery { rnsLxmf.getOutboundPropagationNode() } returns Result.success(nodeHash)
            val requestedHash = slot<String>()
            coEvery {
                identityResolutionManager.forcePathRequest(capture(requestedHash), any())
            } returns true

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.FAILED))
            advanceUntilIdle()

            assertEquals(nodeHash, requestedHash.captured)
        }

    @Test
    fun `a failed propagated message asks for nothing when no node is configured`() =
        runCollectorTest {
            val hash = "failed_propagated_no_node"
            givenRow(hash, message(hash, status = "propagated", deliveryMethod = "propagated"))
            coEvery { rnsLxmf.getOutboundPropagationNode() } returns Result.success(null)

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.FAILED))
            advanceUntilIdle()

            coVerify(exactly = 0) { identityResolutionManager.forcePathRequest(any(), any()) }
        }

    @Test
    fun `a successful delivery does not request a path`() =
        runCollectorTest {
            // A path request is broadcast on every interface. Firing one on a healthy
            // delivery would put that on the air for every message sent.
            val hash = "delivered_hash"
            givenRow(hash, message(hash))
            coEvery { rnsCore.getNextHopInterfaceName(any()) } returns null

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.DELIVERED))
            advanceUntilIdle()

            coVerify(exactly = 0) { identityResolutionManager.forcePathRequest(any(), any()) }
        }

    @Test
    fun `a failed delivery for an unknown message requests nothing`() =
        runCollectorTest {
            val hash = "unknown_failed_hash"
            givenRow(hash, null)

            collector.handleDeliveryStatusUpdate(update(hash, DeliveryStatus.FAILED))
            advanceUntilIdle()

            coVerify(exactly = 0) { identityResolutionManager.forcePathRequest(any(), any()) }
        }

    @Test
    fun `peer activity is recorded only on a delivery proof`() =
        runCollectorTest {
            // A proof is evidence the peer was online; a failure is evidence of
            // nothing, and recording it would keep a dead peer looking fresh.
            val delivered = "activity_delivered"
            val failed = "activity_failed"
            givenRow(delivered, message(delivered))
            givenRow(failed, message(failed))
            coEvery { rnsCore.getNextHopInterfaceName(any()) } returns null

            collector.handleDeliveryStatusUpdate(update(delivered, DeliveryStatus.DELIVERED))
            collector.handleDeliveryStatusUpdate(update(failed, DeliveryStatus.FAILED))
            advanceUntilIdle()

            coVerify(exactly = 1) {
                conversationLinkManager.recordPeerActivity(testPeerHash, 1_700_000_000_000L)
            }
        }
}
