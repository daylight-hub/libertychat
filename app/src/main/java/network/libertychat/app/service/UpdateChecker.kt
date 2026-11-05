package network.libertychat.app.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import network.libertychat.app.BuildConfig
import network.libertychat.app.gateway.GatewayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class AppUpdateResult {
    data object Idle : AppUpdateResult()

    data object Checking : AppUpdateResult()

    data class UpToDate(
        val currentVersion: String,
    ) : AppUpdateResult()

    data class UpdateAvailable(
        val currentVersion: String,
        val tagName: String,
        val htmlUrl: String,
        val isPrerelease: Boolean,
        /** Direct download of the same APK as the one installed (same ABI or universal), if found. */
        val apkUrl: String? = null,
        /** The new version, e.g. "2.3.6". */
        val latestVersion: String = tagName.removePrefix("v"),
        /** The APK's file name, if one matched. */
        val fileName: String? = null,
        /** Where it was found: GitHub, or the LCS download mirror. */
        val source: String = UpdateChecker.SOURCE_GITHUB,
        /** Release notes in plain text (GitHub only). */
        val notes: String = "",
        /** The build installed on this phone, e.g. "arm64-v8a" or "universal". */
        val variant: String = "universal",
    ) : AppUpdateResult()

    data class Error(
        val message: String,
    ) : AppUpdateResult()
}

/**
 * LCS: finds out whether a newer Liberty Chat is out, and where to get the same build as the
 * one installed (same backend, same processor type).
 *
 * GitHub releases come first. If GitHub can't be reached (or its release has no matching APK),
 * the LCS download mirror is checked instead: https://public.lcs.network/downloads/.
 *
 * With "Connect through Tor" on for LCS Global, both checks go through Orbot too, so the
 * check never reveals this phone's internet address when the person has asked for Tor.
 * Nothing about the phone is sent: these are plain downloads of public pages.
 */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "UpdateChecker"
            private const val OWNER = "daylight-hub"
            private const val REPO = "libertychat"
            private const val TIMEOUT_MS = 15_000
            private const val TOR_TIMEOUT_MS = 45_000
            private const val PREF_USE_TOR = "use_tor"

            const val MIRROR_URL = "https://public.lcs.network/downloads/"
            const val SOURCE_GITHUB = "GitHub"
            const val SOURCE_MIRROR = "lcs.network"

            /** Every Liberty Communication Systems app, for the "All apps" link. */
            const val ALL_APPS_URL = "https://lcs.network/#download-the-app"
        }

        private data class GitHubRelease(
            val version: List<Int>,
            val tagName: String,
            val htmlUrl: String,
            val isPrerelease: Boolean,
            val notes: String,
            val apks: List<Pair<String, String>>,
        )

        suspend fun check(includePrerelease: Boolean): AppUpdateResult =
            withContext(Dispatchers.IO) {
                val current = UpdateSelection.parseVersion(BuildConfig.VERSION_NAME)
                if (current.isEmpty()) return@withContext AppUpdateResult.Error("Could not read this app's version")
                val currentText = UpdateSelection.format(current)
                val backend = if (BuildConfig.RNS_IMPL == "kotlin") "rns-kt" else "rns-py"
                val variant = runCatching { installedVariant() }.getOrDefault("universal")
                val proxy = torProxy()

                val github =
                    runCatching { latestGitHubRelease(includePrerelease, proxy) }
                        .onFailure { Log.w(TAG, "GitHub update check failed: ${it.message}") }
                        .getOrNull()
                val githubOffer =
                    github?.takeIf { UpdateSelection.isNewer(it.version, current) }?.let { rel ->
                        val apk = UpdateSelection.pick(rel.apks, backend, variant)
                        AppUpdateResult.UpdateAvailable(
                            currentVersion = currentText,
                            tagName = rel.tagName,
                            htmlUrl = rel.htmlUrl,
                            isPrerelease = rel.isPrerelease,
                            apkUrl = apk?.second,
                            latestVersion = UpdateSelection.format(rel.version),
                            fileName = apk?.first,
                            source = SOURCE_GITHUB,
                            notes = rel.notes,
                            variant = variant,
                        )
                    }

                // The mirror is the fallback: GitHub unreachable, or no matching APK there.
                var mirrorReached = false
                val mirrorOffer =
                    if (github == null || (githubOffer != null && githubOffer.apkUrl == null)) {
                        runCatching {
                            val listing = fetch(MIRROR_URL, "text/html", proxy)
                            mirrorReached = true
                            UpdateSelection.newestOnMirror(listing, MIRROR_URL, backend, variant)
                        }.onFailure { Log.w(TAG, "Mirror update check failed: ${it.message}") }
                            .getOrNull()
                            ?.takeIf { UpdateSelection.isNewer(it.version, current) }
                            ?.let { apk ->
                                val version = UpdateSelection.format(apk.version)
                                AppUpdateResult.UpdateAvailable(
                                    currentVersion = currentText,
                                    tagName = "v$version",
                                    htmlUrl = MIRROR_URL,
                                    isPrerelease = false,
                                    apkUrl = apk.url,
                                    latestVersion = version,
                                    fileName = apk.name,
                                    source = SOURCE_MIRROR,
                                    variant = variant,
                                )
                            }
                    } else {
                        null
                    }

                when {
                    githubOffer != null && githubOffer.apkUrl != null -> githubOffer
                    mirrorOffer != null &&
                        (githubOffer == null || UpdateSelection.compare(
                            UpdateSelection.parseVersion(mirrorOffer.latestVersion),
                            UpdateSelection.parseVersion(githubOffer.latestVersion),
                        ) >= 0) -> mirrorOffer
                    githubOffer != null -> githubOffer
                    github != null || mirrorReached -> AppUpdateResult.UpToDate(currentVersion = currentText)
                    proxy != null -> AppUpdateResult.Error("Couldn't check for updates through Tor. Is Orbot running?")
                    else -> AppUpdateResult.Error("Couldn't check for updates. Check your internet connection.")
                }
            }

        private fun latestGitHubRelease(
            includePrerelease: Boolean,
            proxy: Proxy?,
        ): GitHubRelease? {
            val release: JSONObject =
                if (includePrerelease) {
                    val list = JSONArray(fetch("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10", "application/vnd.github+json", proxy))
                    (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { !it.optBoolean("draft") }
                        ?: return null
                } else {
                    JSONObject(fetch("https://api.github.com/repos/$OWNER/$REPO/releases/latest", "application/vnd.github+json", proxy))
                }
            val tag = release.optString("tag_name")
            val version = UpdateSelection.parseVersion(tag)
            if (version.isEmpty()) throw IOException("Could not parse release info")
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val apks =
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .map { it.optString("name") to it.optString("browser_download_url") }
                    .filter { (name, url) -> name.endsWith(".apk") && url.isNotEmpty() }
            return GitHubRelease(
                version = version,
                tagName = tag,
                htmlUrl = release.optString("html_url").ifEmpty { "https://github.com/$OWNER/$REPO/releases/latest" },
                isPrerelease = release.optBoolean("prerelease"),
                notes = UpdateSelection.tidyNotes(release.optString("body")),
                apks = apks,
            )
        }

        /** Orbot's SOCKS proxy when LCS Global is set to connect through Tor; otherwise direct. */
        private fun torProxy(): Proxy? {
            val useTor =
                context.getSharedPreferences(GatewayConfig.PREFS, Context.MODE_PRIVATE).getBoolean(PREF_USE_TOR, false)
            if (!useTor) return null
            return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(GatewayConfig.TOR_SOCKS_HOST, GatewayConfig.TOR_SOCKS_PORT))
        }

        /** Which of the four APKs is installed: one ABI's libraries, or several (universal). */
        private fun installedVariant(): String {
            val info = context.applicationInfo
            val abis = mutableSetOf<String>()
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path ->
                runCatching {
                    ZipFile(path).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            .filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                            .forEach { abis += it.removePrefix("lib/").substringBefore('/') }
                    }
                }
            }
            return if (abis.size == 1 && abis.first() in UpdateSelection.VARIANTS) abis.first() else "universal"
        }

        private fun fetch(
            url: String,
            accept: String,
            proxy: Proxy?,
        ): String {
            val connection = (if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()) as HttpURLConnection
            val timeout = if (proxy != null) TOR_TIMEOUT_MS else TIMEOUT_MS
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty("Accept", accept)
            if (url.startsWith("https://api.github.com/")) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "LibertyChat/${BuildConfig.VERSION_NAME}")
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $responseCode")
                }
                return connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
