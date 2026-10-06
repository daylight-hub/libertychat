package network.libertychat.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LCS: unit tests for the offered TCP server list.
 *
 * These pin down two things the UI and onboarding both depend on: that the
 * Command Center entry exists with the right host/port, and that adding it did
 * not disturb which server onboarding picks by default.
 */
class TcpCommunityServersTest {
    @Test
    fun `command center entry is present with the LCS host and port`() {
        val server = TcpCommunityServers.commandCenter

        assertNotNull("Command Center entry must be offered", server)
        requireNotNull(server)
        assertEquals("Command Center PRO Client", server.name)
        assertEquals("liberty.local", server.host)
        assertEquals(4246, server.port)
    }

    @Test
    fun `command center is never a bootstrap interface`() {
        // A bootstrap interface auto-detaches once enough discovered interfaces
        // are connected. That is the wrong behaviour for the user's own Command
        // Center, which should stay attached for as long as it is reachable.
        val server = requireNotNull(TcpCommunityServers.commandCenter)

        assertFalse(server.isBootstrap)
    }

    @Test
    fun `command center carries a note explaining when to use it`() {
        val server = requireNotNull(TcpCommunityServers.commandCenter)

        assertNotNull("The picker needs a hint for this entry", server.note)
        assertTrue(
            "Note should point at the AutoInterface fallback case, was: ${server.note}",
            server.note!!.contains("AutoInterface"),
        )
    }

    @Test
    fun `command center is listed after the local IP RNode`() {
        // The request was for this entry to sit under the IP RNode in the picker.
        val hosts = TcpCommunityServers.servers.map { it.host }

        assertTrue(
            "Expected Command Center to follow iprnode.local, got $hosts",
            hosts.indexOf("liberty.local") > hosts.indexOf("iprnode.local"),
        )
    }

    @Test
    fun `first server is still the LCS public node`() {
        // Onboarding's "Internet (TCP)" card takes servers.first(). Adding the
        // Command Center entry must not change what that card connects to.
        val first = TcpCommunityServers.servers.first()

        assertEquals("public.lcs.network", first.host)
        assertEquals(4245, first.port)
        assertTrue(first.isBootstrap)
    }

    @Test
    fun `every offered server has a usable name host and port`() {
        TcpCommunityServers.servers.forEach { server ->
            assertTrue("Blank name in $server", server.name.isNotBlank())
            assertTrue("Blank host in $server", server.host.isNotBlank())
            assertTrue("Port out of range in $server", server.port in 1..65535)
        }
    }

    @Test
    fun `no two offered servers share a host and port`() {
        val targets = TcpCommunityServers.servers.map { "${it.host}:${it.port}" }

        assertEquals(
            "Duplicate targets would collide in the picker's item key: $targets",
            targets.size,
            targets.toSet().size,
        )
    }
}
