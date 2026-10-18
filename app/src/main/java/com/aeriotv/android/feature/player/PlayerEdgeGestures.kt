package com.aeriotv.android.feature.player

import android.app.Activity
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeriotv.android.core.pip.findActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.preferences.PLAYER_EDGE_LEFT
import com.aeriotv.android.core.preferences.PLAYER_EDGE_RIGHT
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Widest an edge band may be, as a fraction of the player width. Narrow on
 * purpose: the channel-flip drag and the swipe-down-to-minimize both own the
 * middle of the screen, so a slide only counts as brightness / volume when it
 * starts right at the bezel.
 */
private const val EDGE_BAND_FRACTION = 0.10f

/** Hard cap on the edge band so a tablet does not get a huge dead strip. */
private val EDGE_BAND_MAX = 56.dp

/**
 * Top of the screen belongs to `playerTopSwipeDown` (swipe down to minimize).
 * An edge slide starting above this is ignored so the two never race.
 */
private const val EDGE_TOP_EXCLUDE_FRACTION = 0.18f

/** Bottom strip left alone for the system gesture / navigation area. */
private const val EDGE_BOTTOM_EXCLUDE_FRACTION = 0.06f

/**
 * Travel, as a fraction of the player height, that sweeps the full 0..100
 * range. Less than the whole height so a comfortable thumb slide covers it.
 */
private const val EDGE_FULL_RANGE_FRACTION = 0.65f

/** How long the indicator stays up after the finger lifts. */
private const val EDGE_INDICATOR_MS = 900L

/** Brightness floor: never slide all the way to a black screen. */
private const val MIN_BRIGHTNESS = 0.02f

/** What an edge slide is currently adjusting. */
enum class EdgeAdjustKind { Brightness, Volume }

/**
 * Shared, transient state for the edge-slide indicator.
 *
 * The gesture is a [Modifier] so it can live in the SAME chain as the player's
 * tap / drag handlers (an overlapping sibling Box would swallow taps: hit
 * testing stops at the topmost hit sibling). Modifiers cannot emit UI, so the
 * indicator reads this object instead. One player is on screen at a time, so a
 * single holder is enough. Same idiom as VideoScalePinchSignal.
 */
private object EdgeAdjustSignal {
    var kind by mutableStateOf(EdgeAdjustKind.Brightness)

    /** Current level, 0f..1f. */
    var level by mutableFloatStateOf(0f)

    /** Bumped on every change so the indicator can restart its dwell. */
    var serial by mutableIntStateOf(0)

    /** True while a finger is still down on an edge. */
    var active by mutableStateOf(false)

    fun publish(k: EdgeAdjustKind, value: Float, stillDown: Boolean) {
        kind = k
        level = value.coerceIn(0f, 1f)
        active = stillDown
        serial++
    }
}

/**
 * Vertical slide along one screen edge adjusts brightness, the other volume.
 *
 * Touch only (phone and tablet); pass `enabled = false` on TV. Both halves are
 * off by default and are gated independently, so one edge can be live while
 * the other does nothing.
 *
 * Add this to the same modifier chain as the tap / channel-flip layer, BEFORE
 * `playerTopSwipeDown` (which must stay last). The gesture claims the pointer
 * only once the finger has travelled vertically past touch slop inside an edge
 * band, so taps to toggle chrome and two-finger pinches are untouched; a slide
 * that starts in the middle of the screen is never seen here at all.
 *
 * Brightness is applied to the activity window's `screenBrightness` attribute,
 * so it affects this app only and is restored on exit (see
 * [PlayerEdgeAdjustOverlay]). Volume goes through AudioManager STREAM_MUSIC.
 */
fun Modifier.playerEdgeSlideGestures(
    enabled: Boolean,
    brightnessEnabled: Boolean,
    volumeEnabled: Boolean,
    brightnessEdge: String,
): Modifier = if (!enabled || (!brightnessEnabled && !volumeEnabled)) {
    this
} else {
    this.composed {
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }
        val audio = remember(context) {
            context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
        }
        val brightnessOnLeft = brightnessEdge != PLAYER_EDGE_RIGHT
        pointerInput(brightnessEnabled, volumeEnabled, brightnessOnLeft, activity, audio) {
            val bandPx = minOf(size.width * EDGE_BAND_FRACTION, EDGE_BAND_MAX.toPx())
            val slop = viewConfiguration.touchSlop
            val travelPx = (size.height * EDGE_FULL_RANGE_FRACTION).coerceAtLeast(1f)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val onLeft = down.position.x <= bandPx
                val onRight = down.position.x >= size.width - bandPx
                if (!onLeft && !onRight) return@awaitEachGesture
                if (down.position.y < size.height * EDGE_TOP_EXCLUDE_FRACTION) return@awaitEachGesture
                if (down.position.y > size.height * (1f - EDGE_BOTTOM_EXCLUDE_FRACTION)) return@awaitEachGesture

                val kind = if (onLeft == brightnessOnLeft) EdgeAdjustKind.Brightness else EdgeAdjustKind.Volume
                val live = when (kind) {
                    EdgeAdjustKind.Brightness -> brightnessEnabled
                    EdgeAdjustKind.Volume -> volumeEnabled
                }
                if (!live) return@awaitEachGesture

                val maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                if (kind == EdgeAdjustKind.Volume && (audio == null || maxVolume <= 0)) return@awaitEachGesture
                if (kind == EdgeAdjustKind.Brightness && activity == null) return@awaitEachGesture

                // Start from where the system actually is, so the first
                // movement nudges rather than jumps.
                var level = when (kind) {
                    EdgeAdjustKind.Brightness -> currentAppBrightness(activity)
                    EdgeAdjustKind.Volume ->
                        audio!!.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                }.coerceIn(0f, 1f)

                var claimed = false
                var lastY = down.position.y
                while (true) {
                    val event = awaitPointerEvent()
                    // A second finger is the Fit / Fill pinch: let it have the gesture.
                    if (event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dyTotal = change.position.y - down.position.y
                    val dxTotal = change.position.x - down.position.x
                    if (!claimed) {
                        // Horizontal wander first: not ours, stand down.
                        if (abs(dxTotal) > slop && abs(dxTotal) > abs(dyTotal)) break
                        if (abs(dyTotal) <= slop) continue
                        claimed = true
                        lastY = change.position.y
                    }
                    change.consume()
                    // Up is more, down is less.
                    level = (level - (change.position.y - lastY) / travelPx).coerceIn(0f, 1f)
                    lastY = change.position.y
                    when (kind) {
                        EdgeAdjustKind.Brightness -> applyAppBrightness(activity, level)
                        EdgeAdjustKind.Volume -> audio?.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (level * maxVolume).roundToInt().coerceIn(0, maxVolume),
                            0,
                        )
                    }
                    EdgeAdjustSignal.publish(kind, level, stillDown = true)
                }
                if (claimed) EdgeAdjustSignal.publish(kind, level, stillDown = false)
            }
        }
    }
}

/** Current app-window brightness, falling back to a mid value when unset. */
private fun currentAppBrightness(activity: Activity?): Float {
    val attrs = activity?.window?.attributes ?: return 0.5f
    val current = attrs.screenBrightness
    // BRIGHTNESS_OVERRIDE_NONE (-1f) means "follow the system"; the real system
    // level is not readable without WRITE_SETTINGS, so start from the middle.
    return if (current < 0f) 0.5f else current
}

private fun applyAppBrightness(activity: Activity?, level: Float) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = level.coerceIn(MIN_BRIGHTNESS, 1f)
    }
}

/**
 * Transient indicator for [playerEdgeSlideGestures], plus the restore of the
 * app's brightness override when the player goes away.
 *
 * Purely decorative: it installs no pointer input, so the tap layer underneath
 * keeps receiving every touch. Both the bar and all of its state live here
 * because the player's top-level composables sit near the JVM verifier's
 * register limit, so hosting is one line.
 */
@Composable
fun BoxScope.PlayerEdgeAdjustOverlay(
    enabled: Boolean,
    settingsVm: com.aeriotv.android.feature.settings.SettingsViewModel =
        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    if (!enabled) return
    val brightnessEdge by settingsVm.playerBrightnessEdge
        .collectAsStateWithLifecycle(initialValue = PLAYER_EDGE_LEFT)
    val brightnessOn by settingsVm.playerBrightnessGesture
        .collectAsStateWithLifecycle(initialValue = false)
    val volumeOn by settingsVm.playerVolumeGesture
        .collectAsStateWithLifecycle(initialValue = false)
    if (!brightnessOn && !volumeOn) return
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // App-only brightness: hand the window back to the system on the way out,
    // whether the user leaves the player, minimizes or backgrounds the app.
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // Ignore whatever a previous player instance left behind: only a slide that
    // happens while this overlay is composed may raise the indicator.
    val baseSerial = remember { EdgeAdjustSignal.serial }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(EdgeAdjustSignal.serial) {
        if (EdgeAdjustSignal.serial == baseSerial) return@LaunchedEffect
        visible = true
        if (EdgeAdjustSignal.active) return@LaunchedEffect
        delay(EDGE_INDICATOR_MS)
        visible = false
    }

    val onLeft = when (EdgeAdjustSignal.kind) {
        EdgeAdjustKind.Brightness -> brightnessEdge != PLAYER_EDGE_RIGHT
        EdgeAdjustKind.Volume -> brightnessEdge == PLAYER_EDGE_RIGHT
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = 20.dp),
    ) {
        val level = EdgeAdjustSignal.level
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = when {
                    EdgeAdjustSignal.kind == EdgeAdjustKind.Brightness -> Icons.Filled.BrightnessHigh
                    level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (EdgeAdjustSignal.kind == EdgeAdjustKind.Brightness) "Brightness" else "Volume",
                tint = Color.White,
            )
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(6.dp)
                    .height(120.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White),
                )
            }
            Text(
                text = "${(level * 100f).roundToInt()}%",
                fontSize = 12.sp,
                color = Color.White,
            )
        }
    }
}

/**
 * [playerEdgeSlideGestures] with the settings read here rather than passed in.
 *
 * For call sites that cannot grow parameters (VODPlayerScreen sits at the JVM
 * register limit, so its gesture chain is assembled in
 * PhoneVodMini.vodTapLayerGestures). Same edge bands, same exclusions, same
 * app-only brightness restore, since it is the same implementation.
 */
fun Modifier.playerEdgeSlideGesturesFromSettings(
    settingsVm: com.aeriotv.android.feature.settings.SettingsViewModel,
    enabled: Boolean,
): Modifier = composed {
    val brightnessOn by settingsVm.playerBrightnessGesture
        .collectAsStateWithLifecycle(initialValue = false)
    val volumeOn by settingsVm.playerVolumeGesture
        .collectAsStateWithLifecycle(initialValue = false)
    val edge by settingsVm.playerBrightnessEdge
        .collectAsStateWithLifecycle(initialValue = PLAYER_EDGE_LEFT)
    playerEdgeSlideGestures(
        enabled = enabled,
        brightnessEnabled = brightnessOn,
        volumeEnabled = volumeOn,
        brightnessEdge = edge,
    )
}
