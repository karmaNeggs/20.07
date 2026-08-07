package org.offlinemesh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.offlinemesh.app.ble.MeshService
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One plottable point: distance/bearing already forward-relative (0° = straight ahead).
 *  [ageSeconds] defaults to 0 (a fresh, just-computed point, e.g. this device's own "you" marker
 *  has no independent age concept) — peer dots pass the real age of the position record they were
 *  placed from, so [RadarCanvas] can fade a dot that's gone stale instead of it looking exactly as
 *  live as one just received.
 *
 *  [maxAgeSeconds] is that same dot's OWN staleness budget — `PositionTracker
 *  .effectiveMaxAgeSecondsFor(record.hop)`, not a flat constant (decision 33, `docs/DECISIONS.md`):
 *  once `maxPositionRelayHops` grew large enough for genuinely long relay chains, a flat fade
 *  window sized for a hop-0/1 reading would leave every dot beyond a few hops fully min-faded for
 *  the ENTIRE rest of its actual eligibility window (which can now run to well over an hour at high
 *  hop counts) — every "far and possibly old" dot would look identically ghostly, with no visual
 *  difference between one that just barely relayed in and one about to expire. Scaling the fade
 *  window to match [PositionTracker]'s own real per-hop staleness budget keeps that distinction
 *  meaningful at any hop count. Defaults to the old flat 180s for any caller that hasn't been
 *  updated to pass a real value. */
data class RadarDot(
    val color: Color,
    val distanceMeters: Float,
    val screenAngleDegrees: Float,
    val ageSeconds: Float = 0f,
    val maxAgeSeconds: Float = 180f,
)

/** Distance + forward-relative screen angle for one peer. */
data class RadarPlacement(val distanceMeters: Float, val screenAngleDegrees: Float)

/**
 * Shown instead of a radar — on Home, Group chat, and Navigate alike — whenever the mesh can't
 * possibly be delivering anything right now, for either of two independent reasons: the OS's
 * actual Bluetooth adapter is off (`MeshService.bluetoothEnabled`), or the app's own "Offline"
 * toggle is on (`MeshService.meshActive` false) even though Bluetooth itself is fine. Without
 * this, a radar would keep showing whatever peer positions/hop counts were last cached, looking
 * exactly as live as normal even though nothing could possibly be arriving anymore; all three
 * screens go in and out of this state together since they share the same two flows instead of
 * each polling independently. [title]/[subtitle] default to the Bluetooth-off copy; callers pass
 * different text for the offline-toggle case so the message stays accurate to the actual cause.
 */
@Composable
fun MeshPausedNotice(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 260.dp,
    title: String = "Bluetooth is off",
    subtitle: String = "Turn it on to find your group",
) {
    Box(
        modifier.size(sizeDp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = AppColors.Warning)
            Text(
                title, color = AppColors.OnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                subtitle, color = AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Combined GPS uncertainty (my accuracy + the peer's, in metres) beyond which a relative
 * bearing/distance isn't trustworthy enough to draw. Outdoor GPS is a few metres, so this rarely
 * touches normal use; it mainly rejects network-location / very-rough indoor fixes, which would
 * otherwise plot a confident-looking dot that's actually tens-to-hundreds of metres wrong — the
 * "the distances don't make sense" case when two phones can't see much sky.
 *
 * Set to comfortably clear the radar's own [ringScaleLadder] display ceiling (200m) — not just
 * "reject obviously nonsense" — after a live-confirmed pattern: many phones deliberately *widen*
 * their reported GPS accuracy while stationary (less continuous satellite tracking to save
 * battery when you're not moving), then tighten it back up the moment motion is detected. At the
 * previous, tighter threshold (150m) this made a peer's dot disappear while genuinely standing
 * still — even side by side with zero real position change — and reappear on the next step, which
 * reads as broken far more often than it protects against an actually-wrong dot. Tunable in one
 * place.
 */
const val ROUGH_FIX_METERS = 250f

/** Collects [MeshService.radarTick] (or a neutral placeholder if the service isn't bound yet) —
 *  the one shared replacement for what used to be three near-identical per-screen polling loops
 *  (Home/GroupChat/Navigate), each independently re-reading location/heading on its own
 *  `while(true)`/`delay(1000)` timer. Extracted here (not left duplicated at each of the three
 *  call sites) once it became clear all three needed the exact same one-line fallback — same
 *  established pattern this app already uses for `bluetoothEnabled`/`meshActive` elsewhere
 *  (conditionally calling `collectAsState()` behind a null check on the same service instance for
 *  the composition's lifetime). */
@Composable
fun rememberRadarTick(meshService: MeshService?): MeshService.RadarTick {
    val collected = meshService?.radarTick?.collectAsState()
    return collected?.value ?: remember {
        MeshService.RadarTick(location = null, headingDegrees = 0f, compassLowAccuracy = false)
    }
}

/**
 * The single place peer distance/bearing + forward-up rotation is computed — previously copy-pasted
 * into Home, Group chat, and Navigate, which is exactly how three screens quietly drift. Returns
 * null when the fix is too rough to place honestly (see [ROUGH_FIX_METERS]); callers then simply
 * don't draw that peer (and Navigate still shows its Bluetooth hop-count fallback).
 */
fun placePeerOnRadar(
    meLat: Double, meLon: Double, meAccuracyM: Float,
    peerLat: Double, peerLon: Double, peerAccuracyM: Int,
    headingDegrees: Float,
): RadarPlacement? {
    if (meAccuracyM + peerAccuracyM >= ROUGH_FIX_METERS) return null
    val results = FloatArray(3)
    android.location.Location.distanceBetween(meLat, meLon, peerLat, peerLon, results)
    // results[1] is the initial bearing in [-180,180]; subtract our heading for "up = ahead" and
    // normalise to a clean [0,360). (Kotlin's % keeps the dividend's sign, so a bare `% 360` here
    // could stay negative — harmless for the trig that plots it, but wrong as an angle value.)
    val screenAngle = (((results[1] - headingDegrees) % 360f) + 360f) % 360f
    return RadarPlacement(results[0], screenAngle)
}

/**
 * Sonar/scope aesthetic — glowing rings, cardinal ticks, a center crosshair, and a slow rotating
 * sweep — shared between the per-group Navigate screen (one color) and the home dashboard (many
 * groups' colors at once). Each dot pulses at a rate tied to its own distance — closer blinks
 * faster — rather than encoding proximity in color, since color already means something else
 * everywhere in this app (group identity here, reserved red/green elsewhere).
 */
// Fixed ring scales, each evenly divisible by 4 so the outer ring's label is always a round number
// instead of whatever quarter of "however far the current farthest dot happens to be" landed on
// screen (the previous behavior: rings continuously auto-scaled to the farthest live dot, so labels
// shifted with every step someone took). Capped at 200m, deliberately — this radar answers "which
// way, and roughly how far, to the nearest people," not "track someone half a kilometre away."
// Anyone farther than the outer ring simply sits pinned to the rim, direction still correct, exact
// distance not distinguished past that point (see the `.coerceIn(0f, 1f)` below) — the radar zooms
// in for a tight cluster nearby but never zooms back out past this ceiling for one far outlier.
private val ringScaleLadder = listOf(20, 40, 80, 200)

// See the stale-dot fade in RadarCanvas's draw loop below — the fade is the only thing
// distinguishing "here now" from "was here a while ago", and a dot that stays fully opaque until
// the instant it vanishes is worse than no dot — someone walks toward a position that may be out
// of date at walking pace. START stays flat regardless of hop (even a fresh hop-0 dot should start
// visibly "settling" after half a minute); the fade's END used to be flat too (180s, PositionTracker's
// old base window) but is now per-dot (RadarDot.maxAgeSeconds, decision 33, docs/DECISIONS.md) —
// see that field's own doc for why a flat END stopped making sense once relay hop counts grew large
// enough for genuinely long chains. MIN_ALPHA is lower than it once was for the same "nearly-expired
// dot should read as a ghost" reason.
private const val STALE_FADE_START_SECONDS = 30f
private const val STALE_FADE_MIN_ALPHA = 0.2f

// A blinking dot's alpha never dips below this floor (live feedback 2026-08-06: blink cadence was
// fine, but the dip on each cycle read as too soft — see the dot-drawing loop below).
private const val DOT_BLINK_MIN_ALPHA = 0.65f

private fun ringScaleFor(farthestMeters: Float): Int =
    ringScaleLadder.firstOrNull { it >= farthestMeters } ?: ringScaleLadder.last()

@Composable
fun RadarCanvas(dots: List<RadarDot>, headingDegrees: Float, modifier: Modifier = Modifier, sizeDp: Dp = 260.dp) {
    val maxDistance = ringScaleFor(dots.maxOfOrNull { it.distanceMeters } ?: 0f).toFloat()
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            withInfiniteAnimationFrameMillis { elapsedMs = System.currentTimeMillis() - start }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepAngle"
    )
    val textMeasurer = rememberTextMeasurer()

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = modifier.size(sizeDp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val maxRadius = size.minDimension / 2 - 18f

            // Static "this half is roughly ahead of you" wash across the top semicircle — not
            // rotating like the sweep below, always fixed to screen-up. Compose's drawArc angles run
            // clockwise from 0°=3-o'clock, so the top half spans 180° (9 o'clock) through 270°
            // (12 o'clock / straight ahead) to 360°/0° (3 o'clock); a sweepGradient's stop fractions
            // map linearly onto that same 0–360° span, so 0.5/0.75/1.0 land exactly on those three
            // points — the two ends (180°/9-o'clock and 360°/3-o'clock) stay fully transparent, only
            // the peak at 270°/straight-ahead carries any color. Same green as everything else on the
            // radar, peak alpha 0.26 (0.08 -> 0.16 -> this, live feedback 2026-08-06 wanted the
            // radar reading as more green/luminous overall — still a background hint, not something
            // that competes with ring lines or dot colors for attention).
            drawArc(
                brush = Brush.sweepGradient(
                    0.5f to Color.Transparent,
                    0.75f to AppColors.Safe.copy(alpha = 0.26f),
                    1.0f to Color.Transparent,
                    center = center
                ),
                startAngle = 180f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                size = Size(maxRadius * 2, maxRadius * 2)
            )

            // Rotating sweep wedge, faint — a live "still scanning" cue, not just a static image.
            rotate(degrees = sweepAngle, pivot = center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.85f to Color.Transparent,
                        1f to AppColors.Safe.copy(alpha = 0.26f),
                        center = center
                    ),
                    startAngle = 0f, sweepAngle = 360f, useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = Size(maxRadius * 2, maxRadius * 2)
                )
            }

            // Glow rings — layered strokes with falling alpha to fake a soft bloom. Only the
            // outermost ring is labeled with its distance (previously all four were — decluttered
            // to the one number that actually matters: what does the edge of this radar mean).
            // The label sits on the upper-right diagonal, clear of the crosshair and cardinal ticks.
            val labelAngleRad = Math.toRadians((45 - 90).toDouble())
            for (i in 1..4) {
                val r = maxRadius * i / 4
                // Alphas raised twice now: an original 0.06/0.35 (close to invisible in direct sun
                // or screen glare), then 0.14/0.5, then this (live feedback 2026-08-06 wanted more
                // green, more luminous specifically on the radar) — brighter still stays a
                // background hint, not something that competes with dot colors, which sit well
                // above these.
                drawCircle(
                    color = AppColors.Safe.copy(alpha = 0.24f), radius = r, center = center, style = Stroke(width = 7f)
                )
                drawCircle(
                    color = AppColors.Safe.copy(alpha = 0.68f), radius = r, center = center,
                    style = Stroke(width = 1.4f)
                )
            }
            run {
                val lx = center.x + maxRadius * cos(labelAngleRad).toFloat()
                val ly = center.y + maxRadius * sin(labelAngleRad).toFloat()
                drawText(
                    textMeasurer = textMeasurer,
                    text = "${maxDistance.toInt()}m",
                    topLeft = Offset(lx + 3f, ly - 12f),
                    style = TextStyle(color = AppColors.Safe.copy(alpha = 0.78f), fontSize = 9.sp)
                )
            }

            // Center crosshair — alpha raised twice now: 0.25, then 0.4, then this, same
            // outdoor-legibility / "more luminous green" reasoning as the rings above.
            drawLine(
                AppColors.Safe.copy(alpha = 0.55f),
                Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1f
            )
            drawLine(
                AppColors.Safe.copy(alpha = 0.55f),
                Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1f
            )

            // Cardinal ticks (N/E/S/W relative to current facing, so "up" tick = straight ahead)
            for (deg in listOf(0, 90, 180, 270)) {
                val rad = Math.toRadians((deg - 90).toDouble())
                val inner = Offset(center.x + (maxRadius - 8f) * cos(rad).toFloat(), center.y + (maxRadius - 8f) * sin(rad).toFloat())
                val outer = Offset(center.x + (maxRadius + 6f) * cos(rad).toFloat(), center.y + (maxRadius + 6f) * sin(rad).toFloat())
                drawLine(AppColors.Safe.copy(alpha = 0.75f), inner, outer, strokeWidth = 2f)
            }

            // "you" marker — deliberately neutral (theme's own primary content color), not red or
            // green, both reserved elsewhere. AppColors.OnSurface rather than a hardcoded white so
            // this still reads correctly in light mode (a literal white dot would vanish against a
            // white radar surface).
            drawCircle(color = AppColors.OnSurface.copy(alpha = 0.9f), radius = 5f, center = center)
            drawCircle(
                color = AppColors.OnSurface.copy(alpha = 0.25f), radius = 10f,
                center = center, style = Stroke(width = 1f)
            )

            // North reference — separate from the cardinal "ahead" tick above. An arrowhead
            // (roughly double the previous plain dot's footprint) rather than a dot, pointing
            // outward along the same radial line, so it reads at a glance as "this way is north"
            // instead of just a marker to notice.
            val northScreenAngle = ((0f - headingDegrees) + 360) % 360
            val northRad = Math.toRadians((northScreenAngle - 90).toDouble())
            val northAnchorDist = maxRadius + 14f
            val northTipDist = northAnchorDist + 6f
            val northBaseDist = northAnchorDist - 6f
            val northArrowHalfWidth = 7f
            val northTip = Offset(
                center.x + northTipDist * cos(northRad).toFloat(), center.y + northTipDist * sin(northRad).toFloat()
            )
            val northBaseCenter = Offset(
                center.x + northBaseDist * cos(northRad).toFloat(), center.y + northBaseDist * sin(northRad).toFloat()
            )
            val northPerpRad = northRad + PI / 2
            val northBase1 = Offset(
                northBaseCenter.x + northArrowHalfWidth * cos(northPerpRad).toFloat(),
                northBaseCenter.y + northArrowHalfWidth * sin(northPerpRad).toFloat()
            )
            val northBase2 = Offset(
                northBaseCenter.x - northArrowHalfWidth * cos(northPerpRad).toFloat(),
                northBaseCenter.y - northArrowHalfWidth * sin(northPerpRad).toFloat()
            )
            val northArrow = Path().apply {
                moveTo(northTip.x, northTip.y)
                lineTo(northBase1.x, northBase1.y)
                lineTo(northBase2.x, northBase2.y)
                close()
            }
            drawPath(northArrow, color = AppColors.OnSurfaceMuted)

            for (dot in dots) {
                val r = (dot.distanceMeters / maxDistance).coerceIn(0f, 1f) * maxRadius
                val angleRad = Math.toRadians((dot.screenAngleDegrees - 90).toDouble())
                val x = center.x + r * cos(angleRad).toFloat()
                val y = center.y + r * sin(angleRad).toFloat()

                // Closer = faster blink: ~2.5Hz when right next to you, ~0.4Hz far out.
                val freqHz = (1.0 / (0.3 + dot.distanceMeters / 40.0)).coerceIn(0.4, 2.5)
                val phase = (sin(2 * PI * freqHz * elapsedMs / 1000.0).toFloat() + 1f) / 2f
                // Alpha floor raised from 0.5 to DOT_BLINK_MIN_ALPHA — the core never gets dim
                // enough to look washed out, only the halo below carries the softer look.
                val alpha = DOT_BLINK_MIN_ALPHA + (1f - DOT_BLINK_MIN_ALPHA) * phase
                val radius = 7f + 5f * phase
                // A position this stale isn't necessarily wrong, but it's not "live" either — fade
                // it down rather than let it read exactly as fresh as one just received. Full
                // opacity through STALE_FADE_START_SECONDS, linearly down to STALE_FADE_MIN_ALPHA by
                // dot.maxAgeSeconds — that dot's OWN staleness budget (decision 33), not a flat
                // window; past that the record is gone from the mesh entirely (PositionTracker.
                // forGroup's own expiry), not just faded.
                val staleProgress = ((dot.ageSeconds - STALE_FADE_START_SECONDS) /
                    (dot.maxAgeSeconds - STALE_FADE_START_SECONDS)).coerceIn(0f, 1f)
                val staleFade = 1f - staleProgress * (1f - STALE_FADE_MIN_ALPHA)

                // Halo tightened from +6f/0.25 alpha (live feedback 2026-08-06: dots wanted to read
                // "a bit sharper") — a smaller, fainter glow leaves the core circle below as the
                // crisp edge the eye actually locks onto, instead of the blur dominating it.
                drawCircle(
                    color = dot.color.copy(alpha = 0.18f * staleFade), radius = radius + 4f, center = Offset(x, y)
                )
                drawCircle(color = dot.color.copy(alpha = alpha * staleFade), radius = radius, center = Offset(x, y))
            }
        }
    }
}
