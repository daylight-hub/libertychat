package network.libertychat.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTabTest {
    @Test
    fun `sanitize falls back to default for null, blank, and all-unknown input`() {
        assertEquals(NavTab.DEFAULT, NavTab.sanitize(null))
        assertEquals(NavTab.DEFAULT, NavTab.sanitize(""))
        assertEquals(NavTab.DEFAULT, NavTab.sanitize(" , ,, bogus"))
    }

    @Test
    fun `sanitize drops unknown ids and dedupes`() {
        assertEquals(
            listOf(NavTab.CHATS, NavTab.MAP, NavTab.SETTINGS),
            NavTab.sanitize("chats,bogus,map,chats"),
        )
    }

    @Test
    fun `sanitize always appends pinned settings last`() {
        assertEquals(
            listOf(NavTab.CHATS, NavTab.CONTACTS, NavTab.SETTINGS),
            NavTab.sanitize("chats,contacts"),
        )
        assertEquals(
            listOf(NavTab.MAP, NavTab.SETTINGS),
            NavTab.sanitize("settings,map,settings"),
        )
    }

    @Test
    fun `sanitize clamps to MAX_TABS including settings`() {
        val result = NavTab.sanitize("chats,announces,contacts,map,nomadnet,settings")
        assertEquals(NavTab.MAX_TABS, result.size)
        assertEquals(NavTab.SETTINGS, result.last())
        // Overflow drops the last editable entries; the first MAX-1 keep their place.
        assertEquals(listOf("chats", "announces", "contacts", "map"), result.dropLast(1).map { it.id })
    }

    @Test
    fun `sanitize of already valid layout is a fixed point`() {
        val valid = listOf(NavTab.MAP, NavTab.NOMADNET, NavTab.SETTINGS)
        val csv = valid.joinToString(",") { it.id }
        assertEquals(valid, NavTab.sanitize(csv))
        assertEquals(valid, NavTab.sanitize(NavTab.sanitize(csv).joinToString(",") { it.id }))
    }

    @Test
    fun `settings can never be removed`() {
        val withoutSettings = NavTab.CONFIGURABLE.take(1).map { it.id }.joinToString(",")
        val result = NavTab.sanitize(withoutSettings)
        assertTrue("Settings must always be present", NavTab.SETTINGS in result)
        assertFalse(NavTab.CONFIGURABLE.contains(NavTab.SETTINGS))
    }

    @Test
    fun `matchesRoute highlights across parameterized routes`() {
        assertTrue(NavTab.ANNOUNCES.matchesRoute("announce_stream?filterType=all"))
        assertTrue(NavTab.NOMADNET.matchesRoute("nomadnet_browser/0123?path=%2F"))
        assertTrue(NavTab.NOMADNET.matchesRoute("nomadnet_home"))
        assertFalse(NavTab.CHATS.matchesRoute("messaging/abc/Name"))
        assertFalse(NavTab.CHATS.matchesRoute(null))
    }

    @Test
    fun `every tab navigates to a route matching its own prefix`() {
        NavTab.entries.forEach { tab ->
            assertTrue(
                "${tab.name} tab route ${tab.tabRoute} must match its own prefix",
                tab.matchesRoute(tab.tabRoute),
            )
        }
    }

    @Test
    fun `the default bar reaches NomadNet without configuration`() {
        // LCS: upstream leaves NomadNet opt-in, which on a fresh install means
        // there is no way into it at all — the per-announce "Browse Site" button
        // only shows for the nomadnetwork.node aspect, so with no such announce
        // received the feature is invisible. It must be in the default bar.
        assertTrue(NavTab.NOMADNET in NavTab.DEFAULT)
        assertTrue(NavTab.NOMADNET in NavTab.sanitize(null))

        // Still a legal bar: pinned Settings present, last, and within the cap.
        assertEquals(NavTab.SETTINGS, NavTab.DEFAULT.last())
        assertTrue(NavTab.DEFAULT.size <= NavTab.MAX_TABS)
    }
}
