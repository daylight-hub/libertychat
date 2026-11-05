package network.libertychat.app.service

/**
 * LCS: the parts of the update check that don't need Android — reading version numbers,
 * picking the right APK out of a release or the download mirror, and tidying release notes
 * for the update popup. Kept separate so they can be tested on a plain JVM.
 *
 * APKs are named like `libertychat-2.3.5-official-rns-py-arm64-v8a.apk`: the version, the
 * backend (`rns-py` or `rns-kt`) and the build (`arm64-v8a`, `armeabi-v7a`, `x86_64` or
 * `universal`). An update is only offered as the same backend and the same build as the one
 * installed; a universal APK is the fallback, since it runs on every phone.
 */
object UpdateSelection {
    /** A downloadable APK: its file name, where to get it, and the version it carries. */
    data class Apk(
        val name: String,
        val url: String,
        val version: List<Int>,
    )

    val VARIANTS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "universal")

    private val APK_NAME =
        Regex("""^libertychat-(\d+(?:\.\d+){1,3})-official-(rns-py|rns-kt)-(arm64-v8a|armeabi-v7a|x86_64|universal)\.apk$""")

    /** "2.3.5", "v2.3.5", "2.3.5-kt", "2.3.5.0012-dev" -> [2, 3, 5]. Empty if there's no version. */
    fun parseVersion(text: String?): List<Int> {
        val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(text.orEmpty()) ?: return emptyList()
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: 0 }
    }

    fun compare(
        a: List<Int>,
        b: List<Int>,
    ): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(
        candidate: List<Int>,
        current: List<Int>,
    ): Boolean = candidate.isNotEmpty() && current.isNotEmpty() && compare(candidate, current) > 0

    fun format(version: List<Int>): String = version.joinToString(".")

    /** The APK in this list that matches the installed backend and build, else the universal one. */
    fun pick(
        apks: List<Pair<String, String>>,
        backend: String,
        variant: String,
    ): Pair<String, String>? {
        val ours =
            apks.filter { (name, url) ->
                val m = APK_NAME.matchEntire(name)
                m != null && m.groupValues[2] == backend && url.startsWith("https://")
            }
        return ours.firstOrNull { (name, _) -> name.endsWith("-$variant.apk") }
            ?: ours.firstOrNull { (name, _) -> name.endsWith("-universal.apk") }
    }

    /**
     * The newest APK on the download mirror (an ordinary web-server folder listing) for this
     * backend and build, falling back to the newest universal APK. Null if there's none.
     */
    fun newestOnMirror(
        listing: String,
        baseUrl: String,
        backend: String,
        variant: String,
    ): Apk? {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val found =
            Regex("""href\s*=\s*["']([^"'?#]+)["']""", RegexOption.IGNORE_CASE)
                .findAll(listing)
                .map { decode(it.groupValues[1].substringAfterLast('/')) }
                .distinct()
                .mapNotNull { name ->
                    val m = APK_NAME.matchEntire(name) ?: return@mapNotNull null
                    if (m.groupValues[2] != backend) return@mapNotNull null
                    Triple(name, parseVersion(m.groupValues[1]), m.groupValues[3])
                }.toList()

        fun newest(build: String) =
            found
                .filter { it.third == build }
                .maxWithOrNull { a, b -> compare(a.second, b.second) }
                ?.let { Apk(it.first, base + it.first, it.second) }

        val exact = newest(variant)
        val universal = if (variant == "universal") null else newest("universal")
        return when {
            exact == null -> universal
            universal != null && compare(universal.version, exact.version) > 0 -> universal
            else -> exact
        }
    }

    /** A plain-text version of GitHub's Markdown release notes, short enough for a popup. */
    fun tidyNotes(
        markdown: String?,
        maxChars: Int = 1500,
    ): String {
        val lines =
            markdown
                .orEmpty()
                .replace("\r\n", "\n")
                .lines()
                .map { line ->
                    line
                        .trimEnd()
                        .replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
                        .replace(Regex("""^(\s*)[-*+]\s+"""), "$1• ")
                        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                        .replace(Regex("""__(.+?)__"""), "$1")
                        .replace(Regex("""`([^`]+)`"""), "$1")
                        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
                }.dropWhile { it.isBlank() }
        val text =
            lines
                .joinToString("\n")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        return cut.substring(0, cut.lastIndexOf('\n').takeIf { it > maxChars / 2 } ?: cut.length).trimEnd() + "\n…"
    }

    /** How the installed build is described to people. */
    fun describeVariant(variant: String): String =
        when (variant) {
            "arm64-v8a" -> "64-bit ARM (arm64-v8a)"
            "armeabi-v7a" -> "32-bit ARM (armeabi-v7a)"
            "x86_64" -> "x86_64"
            else -> "Universal"
        }

    private fun decode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
}
