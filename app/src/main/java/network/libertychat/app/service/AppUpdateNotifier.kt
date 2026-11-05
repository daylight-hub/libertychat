package network.libertychat.app.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import network.libertychat.app.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LCS: the update check that runs when Liberty Chat is opened, and the offer it leaves for the
 * update popup (see AppUpdateHost).
 *
 * The app's process usually outlives its screens (the Reticulum service keeps it alive), so
 * "at startup" means each time the app is opened fresh, at most once every few hours. A
 * dismissed offer comes back at the next check; there's no "don't show again".
 *
 * Settings > Advanced > "Check for updates at startup" turns it off.
 */
@Singleton
class AppUpdateNotifier
    @Inject
    constructor(
        private val updateChecker: UpdateChecker,
        private val settingsRepository: SettingsRepository,
    ) {
        companion object {
            private const val TAG = "AppUpdateNotifier"

            /** Let the splash screen and the first network connections settle first. */
            private const val STARTUP_DELAY_MS = 4_000L

            /** One more try if the phone had no connection yet (or Orbot was still starting). */
            private const val RETRY_DELAY_MS = 90_000L

            /** Opening the app again within this time doesn't check again. */
            private const val MIN_GAP_MS = 6 * 60 * 60 * 1000L
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _offer = MutableStateFlow<AppUpdateResult.UpdateAvailable?>(null)

        /** A newer version waiting to be shown, or null. */
        val offer: StateFlow<AppUpdateResult.UpdateAvailable?> = _offer.asStateFlow()

        @Volatile
        private var lastCheckAt = 0L

        @Volatile
        private var running = false

        /** Called when the app's main screen is created (not on rotation). */
        fun onAppOpened() {
            val now = System.currentTimeMillis()
            if (running || (lastCheckAt != 0L && now - lastCheckAt < MIN_GAP_MS)) return
            running = true
            scope.launch {
                try {
                    if (!settingsRepository.checkUpdatesAtStartupFlow.first()) return@launch
                    delay(STARTUP_DELAY_MS)
                    var result = runCheck()
                    if (result is AppUpdateResult.Error) {
                        delay(RETRY_DELAY_MS)
                        if (!settingsRepository.checkUpdatesAtStartupFlow.first()) return@launch
                        result = runCheck()
                    }
                    if (result !is AppUpdateResult.Error) lastCheckAt = System.currentTimeMillis()
                    if (result is AppUpdateResult.UpdateAvailable) _offer.value = result
                } catch (e: Exception) {
                    Log.w(TAG, "Startup update check failed", e)
                } finally {
                    running = false
                }
            }
        }

        private suspend fun runCheck(): AppUpdateResult {
            val result = updateChecker.check(settingsRepository.includePrereleaseUpdates.first())
            Log.d(TAG, "Startup update check: ${result::class.simpleName}")
            if (result !is AppUpdateResult.Error) {
                settingsRepository.setLastUpdateCheckTime(System.currentTimeMillis())
            }
            return result
        }

        /** "Later", "Download", or the popup was closed: hidden until the next check. */
        fun dismiss() {
            _offer.value = null
        }
    }
