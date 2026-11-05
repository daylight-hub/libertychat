package network.libertychat.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSelectionTest {
    private val mirror = "https://public.lcs.network/downloads/"

    private val listing =
        """
        <tr><td><a href="LCS-ReticulumMeshChat-v2.0.9-linux.AppImage">x</a></td></tr>
        <tr><td><a href="libertychat-1.3.3-official-rns-py-arm64-v8a.apk">x</a></td></tr>
        <tr><td><a href="libertychat-2.1.0-official-rns-py-arm64-v8a.apk">x</a></td></tr>
        <tr><td><a href="libertychat-2.3.4-official-rns-py-arm64-v8a.apk">x</a></td></tr>
        <tr><td><a href="libertychat-2.3.0-official-rns-py-arm64-v8a.apk">x</a></td></tr>
        <tr><td><a href="?C=N;O=D">Name</a></td></tr>
        """.trimIndent()

    @Test
    fun `versions compare by number`() {
        assertEquals(listOf(2, 3, 5), UpdateSelection.parseVersion("v2.3.5"))
        assertEquals(listOf(2, 3, 5), UpdateSelection.parseVersion("2.3.5.0012-dev"))
        assertEquals(listOf(2, 3, 5), UpdateSelection.parseVersion("2.3.5-kt"))
        assertTrue(UpdateSelection.isNewer(listOf(2, 3, 10), listOf(2, 3, 9)))
        assertFalse(UpdateSelection.isNewer(listOf(2, 3, 5), listOf(2, 3, 5)))
    }

    @Test
    fun `mirror gives the newest file of the installed build`() {
        val apk = UpdateSelection.newestOnMirror(listing, mirror, "rns-py", "arm64-v8a")
        assertEquals("libertychat-2.3.4-official-rns-py-arm64-v8a.apk", apk?.name)
        assertEquals(mirror + "libertychat-2.3.4-official-rns-py-arm64-v8a.apk", apk?.url)
        // Another processor type or backend is never offered...
        assertNull(UpdateSelection.newestOnMirror(listing, mirror, "rns-py", "armeabi-v7a"))
        assertNull(UpdateSelection.newestOnMirror(listing, mirror, "rns-kt", "arm64-v8a"))
        // ...except a universal APK, which runs on every phone.
        val withUniversal = listing + "\n<a href=\"libertychat-2.3.6-official-rns-py-universal.apk\">x</a>"
        assertEquals(
            "libertychat-2.3.6-official-rns-py-universal.apk",
            UpdateSelection.newestOnMirror(withUniversal, mirror, "rns-py", "armeabi-v7a")?.name,
        )
    }

    @Test
    fun `release assets pick the same build, then universal`() {
        val apks =
            listOf(
                "libertychat-2.3.5-official-rns-py-arm64-v8a.apk" to "https://g/a",
                "libertychat-2.3.5-official-rns-py-universal.apk" to "https://g/u",
                "libertychat-2.3.5-official-rns-py-x86_64.apk" to "https://g/x",
            )
        assertEquals("https://g/x", UpdateSelection.pick(apks, "rns-py", "x86_64")?.second)
        assertEquals("https://g/u", UpdateSelection.pick(apks, "rns-py", "armeabi-v7a")?.second)
        assertNull(UpdateSelection.pick(apks, "rns-kt", "arm64-v8a"))
    }

    @Test
    fun `release notes become plain text`() {
        val notes = UpdateSelection.tidyNotes("## What's new\r\n\r\n- **Update popup** at startup\n\n\n\nSee [the guide](https://x).")
        assertTrue(notes.startsWith("What's new"))
        assertTrue(notes.contains("• Update popup at startup"))
        assertTrue(notes.contains("See the guide."))
        assertFalse(notes.contains("\n\n\n"))
        assertTrue(UpdateSelection.tidyNotes("a\n".repeat(2000), 100).endsWith("…"))
    }
}
