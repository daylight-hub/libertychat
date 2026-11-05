package network.libertychat.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import network.libertychat.app.R
import network.libertychat.app.ui.theme.LibertyNavy40

/**
 * LCS: the "Liberty Chat" splash wordmark.
 *
 * ## Why the app draws this instead of the system
 *
 * Android's own branding slot, `android:windowSplashScreenBrandingImage`, is
 * API 31+ and platform-only — androidx `core-splashscreen` deliberately does not
 * emulate it, so there is no theme-level way to put the wordmark on Android 10
 * or 11. Relying on it would mean the splash said one thing on a new phone and
 * another on an old one.
 *
 * Embedding the wordmark in the splash *icon* is not a workaround either: on
 * API 31+ the platform masks that icon to a circle and clips the outer third, so
 * a wide logo-plus-text image comes out mangled on exactly the devices where the
 * branding slot would have worked.
 *
 * So the app draws it, on every API level, and the platform branding attribute
 * has been removed from the v31/v33 themes. Every device now shows the same
 * thing: system splash with the logo on white, then this — logo above the
 * wordmark — then the app.
 *
 * ## How it is hosted
 *
 * Declared at the theme root in `MainActivity`, outside the app's `Surface`, so
 * it covers the whole UI while drawing in the same window. Deliberately not a
 * `Dialog`: that would be a separate window, and the frame or two the system
 * takes to attach one is long enough for the app to show through.
 *
 * ## Why [show] exists
 *
 * `setContent` composes the entire tree during `onCreate`, while the system
 * splash is still covering the window. A dwell timer started on composition
 * therefore runs and expires *behind* the splash — which is what happened in
 * 1.2.0, where the wordmark was drawn and removed before the splash ever
 * lifted, leaving users with a logo and no words.
 *
 * [show] is flipped from `SplashScreen.setOnExitAnimationListener` in
 * `MainActivity`, so the dwell starts when the screen is genuinely visible.
 *
 * @param show true once the system splash has left the window.
 * @param visibleDurationMs how long the wordmark stays up before fading.
 */
@Composable
fun LcsBrandedSplashOverlay(
    show: Boolean,
    visibleDurationMs: Long = 2200L,
    onFinished: () -> Unit = {},
) {
    // Latches on the first `show` so a recomposition within this composition
    // cannot replay the splash. Plain remember, NOT rememberSaveable: it must
    // reset on every fresh launch so the splash plays each cold/warm start, and
    // it survives rotation on its own because rotation reuses the composition.
    // (rememberSaveable persisted it across launches, which made the splash play
    // once and never again — and the gate below then opened before the logo.)
    var hasRun by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }

    // Show as soon as the system splash releases.
    LaunchedEffect(show) {
        if (show && !hasRun) {
            hasRun = true
            visible = true
        }
    }

    if (!visible) return

    // Flips true one recomposition after the overlay first shows, so the alpha
    // tween below animates 0 -> 1 (a fade-in) rather than starting at 1.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) appeared = true
    }

    // Start the dwell only once `visible` is true and the overlay has actually
    // been composed — not when `show` flips. The system splash holds the screen
    // until the app has finished loading, which can be most of a second; timing
    // the dwell from `show` meant that load time was subtracted from the 1100 ms
    // and the reader saw only the tail. Anchoring it here gives the full
    // duration of readable, fully-painted branding every launch.
    LaunchedEffect(visible) {
        if (!visible || fading) return@LaunchedEffect
        delay(visibleDurationMs)
        fading = true
        delay(FADE_OUT_MS.toLong())
        visible = false
        onFinished()
    }

    // Cross-fades in over the system splash, then out to the app.
    //
    // The system splash already shows the LCS logo on white; this overlay shows
    // the same logo plus the wordmark on the same white. Easing in (rather than
    // snapping) means the words appear gently and any small difference in how
    // the platform sized or centred its icon versus this one is smoothed over
    // at the seam instead of reading as a jump.
    val target = if (appeared && !fading) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (fading) FADE_OUT_MS else FADE_IN_MS),
        label = "lcs_splash_fade",
    )

    // No Dialog: this draws in the app's own window, so it is on screen the
    // same frame the system splash is removed. A Dialog would need its own
    // window attached first, and the frame or two that takes is enough for the
    // app UI to show through underneath.
    //
    // pointerInput swallows taps so nothing behind can be pressed while the
    // splash is up.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        BoxWithConstraints(
            // Opaque white for the whole lifetime of the overlay. The alpha
            // below is applied to the CONTENT only, not this field, so fading
            // the logo/wordmark in or out never lets the app show through.
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            // Logo and wordmark are stacked as ordinary siblings in one centred
            // Column, with a real gap between them. No overlap tricks: the
            // previous version centred the logo and then offset the text up over
            // it, which only worked for an adaptive-icon foreground with a
            // transparent safe zone. The LCS logo fills its whole image, so that
            // offset pulled the words up into the logo. A Column that just lays
            // them out top-to-bottom needs no assumptions about the artwork.
            val logoSize = minOf(LOGO_SIZE, maxWidth * 0.7f, maxHeight * 0.4f)

            Column(
                modifier =
                    Modifier
                        .alpha(alpha)
                        .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Same logo the system splash already showed — the overlay
                // continues it on the same white field and adds the wordmark,
                // so the handover reads as words appearing, not a new screen.
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(logoSize),
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Liberty Chat",
                    color = LibertyNavy40,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val FADE_IN_MS = 220
private const val FADE_OUT_MS = 300

/**
 * Drawn size of the logo, clamped against the viewport at the call site.
 *
 * The LCS artwork fills its whole image (no transparent safe zone), so this is
 * the actual visible size of the mark.
 */
private val LOGO_SIZE = 200.dp
