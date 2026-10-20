package network.libertychat.app.rns.backend.py

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LCS: tests for [shouldReuseCachedDestination].
 *
 * `PythonRnsLxmf` caches one `RNS.Destination` per recipient, and a destination
 * freezes the `RNS.Identity` it was built from. The cache is only cleared when
 * the whole stack is torn down, so without this check a peer who re-announced
 * under a new identity kept being encrypted to their old public key for the rest
 * of the process's life — a failure that is invisible from the sending side,
 * since the send itself succeeds and only the peer's decryption fails.
 *
 * The asymmetry is the point: a *mismatch* must invalidate, but an *unknown*
 * current identity must not, or a peer whose key RNS has no record of would go
 * from sendable to `IdentityNotFound`.
 */
class CachedDestinationReuseTest {
    private val original = "a1b2c3d4e5f60718a1b2c3d4e5f60718"
    private val rotated = "0f1e2d3c4b5a69780f1e2d3c4b5a6978"

    @Test
    fun `reuses the cached destination while the identity is unchanged`() {
        assertTrue(shouldReuseCachedDestination(original, original))
    }

    @Test
    fun `discards the cached destination once the identity has been replaced`() {
        // The peer reinstalled, switched phones, or regenerated their keys.
        assertFalse(shouldReuseCachedDestination(original, rotated))
    }

    @Test
    fun `keeps the cached destination when RNS has no key on file`() {
        // Identity.recall returned null. The frozen identity is the better of the
        // two; dropping it would turn a sendable peer into IdentityNotFound.
        assertTrue(shouldReuseCachedDestination(original, null))
    }

    @Test
    fun `reuses a cached destination that carries no identity hash`() {
        // Nothing to compare against, so there is no evidence of a rotation.
        assertTrue(shouldReuseCachedDestination(null, original))
        assertTrue(shouldReuseCachedDestination(null, null))
    }

    @Test
    fun `comparison is exact rather than prefix-based`() {
        // Truncated hashes are only ever used for logging; a prefix match here
        // would let a genuinely different identity pass as the cached one.
        assertFalse(shouldReuseCachedDestination(original, original.dropLast(2)))
        assertFalse(shouldReuseCachedDestination(original.take(8), original))
    }
}
