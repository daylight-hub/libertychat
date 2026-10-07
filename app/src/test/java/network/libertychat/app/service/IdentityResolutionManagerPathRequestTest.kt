package network.libertychat.app.service

import network.libertychat.app.data.repository.ContactRepository
import network.libertychat.app.data.repository.ConversationRepository
import network.libertychat.app.rns.api.RnsCore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LCS: tests for [IdentityResolutionManager.forcePathRequest], the path-request
 * entry point used when a message send fails.
 *
 * The behaviour that matters here and isn't covered elsewhere: this call must
 * ignore an existing path (the stale-path case is exactly why it exists), and it
 * must rate-limit itself when called automatically, because a path request is
 * broadcast on every interface and that is expensive on a LoRa link.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityResolutionManagerPathRequestTest {
    private lateinit var contactRepository: ContactRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var rnsCore: RnsCore
    private lateinit var manager: IdentityResolutionManager

    private val peerHash = "a1b2c3d4e5f60718"

    @Before
    fun setup() {
        // Strict mocks: forcePathRequest touches only rnsCore, and the constructor
        // does no work, so the repositories never need to answer anything. (A
        // relaxed mock would also trip the project's NoRelaxedMocks detekt rule.)
        contactRepository = mockk()
        conversationRepository = mockk()
        rnsCore = mockk()
        coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
        manager = IdentityResolutionManager(contactRepository, conversationRepository, rnsCore)
    }

    @Test
    fun `forcePathRequest issues a request even when a path already exists`() =
        runTest {
            // The send-failure case: RNS thinks it has a route, but the route is dead.
            // requestPathIfNeeded would skip this; forcePathRequest must not.
            coEvery { rnsCore.hasPath(any()) } returns true

            val issued = manager.forcePathRequest(peerHash)

            assertTrue("A request should go out despite hasPath being true", issued)
            coVerify(exactly = 1) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `forcePathRequest does not consult hasPath at all`() =
        runTest {
            coEvery { rnsCore.hasPath(any()) } returns true

            val issued = manager.forcePathRequest(peerHash)

            assertTrue(issued)
            // Not merely "ignores the answer" — it shouldn't ask. A hasPath call here
            // would mean someone reintroduced the guard this method exists to skip.
            coVerify(exactly = 0) { rnsCore.hasPath(any()) }
        }

    @Test
    fun `forcePathRequest passes the decoded destination hash to the backend`() =
        runTest {
            val captured = mutableListOf<ByteArray>()
            coEvery { rnsCore.requestPath(capture(captured)) } returns Result.success(Unit)

            manager.forcePathRequest(peerHash)

            assertEquals(1, captured.size)
            assertEquals(
                peerHash,
                captured.first().joinToString("") { "%02x".format(it) },
            )
        }

    @Test
    fun `automatic requests are rate limited per destination`() =
        runTest {
            val first = manager.forcePathRequest(peerHash)
            val second = manager.forcePathRequest(peerHash)

            assertTrue("First automatic request should go out", first)
            assertFalse("Second should be suppressed by the cooldown", second)
            coVerify(exactly = 1) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `the cooldown is per destination, not global`() =
        runTest {
            val otherPeer = "0f1e2d3c4b5a6978"

            val first = manager.forcePathRequest(peerHash)
            val other = manager.forcePathRequest(otherPeer)

            assertTrue(first)
            assertTrue("A different peer must not be blocked by the first one", other)
            coVerify(exactly = 2) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `an explicit request bypasses the cooldown`() =
        runTest {
            // The message-menu action. Silently ignoring a deliberate tap is worse
            // than the airtime, so respectCooldown = false must always transmit.
            manager.forcePathRequest(peerHash)
            val explicit = manager.forcePathRequest(peerHash, respectCooldown = false)

            assertTrue("An explicit request should never be suppressed", explicit)
            coVerify(exactly = 2) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `an explicit request still arms the cooldown for automatic callers`() =
        runTest {
            manager.forcePathRequest(peerHash, respectCooldown = false)
            val automatic = manager.forcePathRequest(peerHash)

            assertFalse(
                "A manual request should still hold off the automatic ones that follow",
                automatic,
            )
            coVerify(exactly = 1) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `forcePathRequest reports failure when the backend rejects the request`() =
        runTest {
            coEvery { rnsCore.requestPath(any()) } returns
                Result.failure(IllegalStateException("no interfaces"))

            val issued = manager.forcePathRequest(peerHash)

            assertFalse("A backend failure should be reported, not swallowed", issued)
        }

    @Test
    fun `a malformed destination hash is rejected without calling the backend`() =
        runTest {
            val oddLength = manager.forcePathRequest("abc")
            val notHex = manager.forcePathRequest("zzzz")
            val blank = manager.forcePathRequest("")

            assertFalse("Odd-length hex is not a hash", oddLength)
            assertFalse("Non-hex characters are not a hash", notHex)
            assertFalse("Blank is not a hash", blank)
            coVerify(exactly = 0) { rnsCore.requestPath(any()) }
        }

    @Test
    fun `a rejected request does not leave the destination permanently blocked`() =
        runTest {
            // A backend failure still arms the cooldown — deliberate, so a retry
            // storm behind a broken interface can't hammer the radio. What must not
            // happen is the malformed-hash path arming it for a hash we never sent.
            manager.forcePathRequest("abc")
            val valid = manager.forcePathRequest(peerHash)

            assertTrue("A bad hash must not block a later valid request", valid)
            coVerify(exactly = 1) { rnsCore.requestPath(any()) }
        }
}
