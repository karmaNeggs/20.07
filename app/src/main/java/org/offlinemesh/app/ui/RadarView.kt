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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One plottable point: distance/bearing already forward-relative (0° = straight ahead). */
data class RadarDot(val color: Color, val distanceMeters: Float, val screenAngleDegrees: Float)

/** Distance + forward-relative screen angle for one peer. */
data class RadarPlacement(val distanceMeters: Float, val screenAngleDegrees: Float)

/**
 * Shown instead of a radar — on Home, Group chat, and Navigate alike, all reading the same
 * `MeshService.bluetoothEnabled` flow — whenever Bluetooth is off. Without this, a radar would keep
 * showing whatever peer positions/hop counts were last cached, looking exactly as live as normal
 * even though nothing could possibly be arriving anymore; all three screens go in and out of this
 * state together since they share one source of truth instead of each polling independently.
 */
@Composable
fun BluetoothOffNotice(modifier: Modifier = Modifier, sizeDp: Dp = 260.dp) {
    Box(
        modifier.size(sizeDp).clip(RoundedCornerShape(20.dp)).background(AppColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.BluetoothDisabled, contentDescription = null, tint = AppColors.Warning)
            Text(
                "Bluetooth is off", color = AppColors.OnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Turn it on to find your group", color = AppColors.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Combined GPS uncertainty (my accuracy + the peer's, in metres) beyond which a relative
 * bearing/distance isn't trustworthy enough to draw. Outdoor GPS is a few metres, so this never
 * touches normal use; it only rejects network-location / very-rough indoor fixes, which would
 * otherwise plot a confident-looking dot that's actually tens-to-hundreds of metres wrong — the
 * "the distances don't make sense" case when two phones can't see much sky. Tunable in one place.
 */
const val ROUGH_FIX_METERS = 150f

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

private val GlowGreen = Color(0xFF34D399)

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
            // radar, peak alpha 0.16 (doubled from an earlier 0.08 — still a background hint, not
            // something that competes with ring lines or dot colors for attention).
            drawArc(
                brush = Brush.sweepGradient(
                    0.5f to Color.Transparent,
                    0.75f to GlowGreen.copy(alpha = 0.16f),
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
                        1f to GlowGreen.copy(alpha = 0.16f),
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
                drawCircle(color = GlowGreen.copy(alpha = 0.06f), radius = r, center = center, style = Stroke(width = 7f))
                drawCircle(color = GlowGreen.copy(alpha = 0.35f), radius = r, center = center, style = Stroke(width = 1.4f))
            }
            run {
                val lx = center.x + maxRadius * cos(labelAngleRad).toFloat()
                val ly = center.y + maxRadius * sin(labelAngleRad).toFloat()
                drawText(
                    textMeasurer = textMeasurer,
                    text = "${maxDistance.toInt()}m",
                    topLeft = Offset(lx + 3f, ly - 12f),
                    style = TextStyle(color = GlowGreen.copy(alpha = 0.6f), fontSize = 9.sp)
                )
            }

            // Center crosshair
            drawLine(GlowGreen.copy(alpha = 0.25f), Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1f)
            drawLine(GlowGreen.copy(alpha = 0.25f), Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1f)

            // Cardinal ticks (N/E/S/W relative to current facing, so "up" tick = straight ahead)
            for (deg in listOf(0, 90, 180, 270)) {
                val rad = Math.toRadians((deg - 90).toDouble())
                val inner = Offset(center.x + (maxRadius - 8f) * cos(rad).toFloat(), center.y + (maxRadius - 8f) * sin(rad).toFloat())
                val outer = Offset(center.x + (maxRadius + 6f) * cos(rad).toFloat(), center.y + (maxRadius + 6f) * sin(rad).toFloat())
                drawLine(GlowGreen.copy(alpha = 0.6f), inner, outer, strokeWidth = 2f)
            }

            // "you" marker — deliberately neutral white, not red or green, both reserved elsewhere
            drawCircle(color = Color.White.copy(alpha = 0.9f), radius = 5f, center = center)
            drawCircle(color = Color.White.copy(alpha = 0.25f), radius = 10f, center = center, style = Stroke(width = 1f))

            // North reference (muted, small) — separate from the cardinal "ahead" tick above
            val northScreenAngle = ((0f - headingDegrees) + 360) % 360
            val northRad = Math.toRadians((northScreenAngle - 90).toDouble())
            val nx = center.x + (maxRadius + 14f) * cos(northRad).toFloat()
            val ny = center.y + (maxRadius + 14f) * sin(northRad).toFloat()
            drawCircle(color = Color(0xFF6B7580), radius = 4f, center = Offset(nx, ny))

            for (dot in dots) {
                val r = (dot.distanceMeters / maxDistance).coerceIn(0f, 1f) * maxRadius
                val angleRad = Math.toRadians((dot.screenAngleDegrees - 90).toDouble())
                val x = center.x + r * cos(angleRad).toFloat()
                val y = center.y + r * sin(angleRad).toFloat()

                // Closer = faster blink: ~2.5Hz when right next to you, ~0.4Hz far out.
                val freqHz = (1.0 / (0.3 + dot.distanceMeters / 40.0)).coerceIn(0.4, 2.5)
                val phase = (sin(2 * PI * freqHz * elapsedMs / 1000.0).toFloat() + 1f) / 2f
                val alpha = 0.5f + 0.5f * phase
                val radius = 7f + 5f * phase

                drawCircle(color = dot.color.copy(alpha = 0.25f), radius = radius + 6f, center = Offset(x, y))
                drawCircle(color = dot.color.copy(alpha = alpha), radius = radius, center = Offset(x, y))
            }
        }
    }
}
