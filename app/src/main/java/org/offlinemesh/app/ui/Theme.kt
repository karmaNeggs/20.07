package org.offlinemesh.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark, low-glare, and deliberately mostly monochrome — color is spent on meaning, not
 * decoration. Three reserved colors, never used for anything else: green = presence/safe,
 * red = SOS/danger, and a soft indigo for generic interactive accents (the default "this is
 * tappable" color when nothing more specific applies). Group identity gets its own separate
 * palette so it's never confused with any of the above.
 */
object AppColors {
    val Background = Color(0xFF0A0D10)
    val Surface = Color(0xFF14181C)
    val SurfaceVariant = Color(0xFF1C2126)
    val OnSurface = Color(0xFFEDEFF1)
    val OnSurfaceMuted = Color(0xFF8B95A1)

    val Safe = Color(0xFF34D399)       // presence / connected / "you're in range" — nothing else
    val Danger = Color(0xFFEF4444)     // SOS only — nothing else ever uses this color
    val Warning = Color(0xFFF59E0B)    // low-confidence / degraded-signal warnings
    val Accent = Color(0xFF818CF8)     // generic interactive accent — default buttons, links

    // Stable per-group palette — excludes red/green/the accent indigo so a group's color can
    // never be mistaken for Danger, Safe, or "just a generic button."
    private val groupPalette = listOf(
        Color(0xFFC084FC), // purple
        Color(0xFFFB923C), // orange
        Color(0xFF22D3EE), // cyan
        Color(0xFFFBBF24), // amber
        Color(0xFFF472B6), // pink
        Color(0xFF38BDF8), // sky
        Color(0xFFD6A77A), // tan
        Color(0xFFFB7185), // rose
    )

    /** Same group id always gets the same color, across restarts and across every screen. */
    fun colorForGroup(groupId: String): Color {
        val idx = (groupId.hashCode().and(Int.MAX_VALUE)) % groupPalette.size
        return groupPalette[idx]
    }
}

private val AppColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    error = AppColors.Danger,
    onError = Color.White,
    background = AppColors.Background,
    onBackground = AppColors.OnSurface,
    surface = AppColors.Surface,
    onSurface = AppColors.OnSurface,
    surfaceVariant = AppColors.SurfaceVariant,
    onSurfaceVariant = AppColors.OnSurfaceMuted,
    secondary = AppColors.Accent,
    onSecondary = Color.White,
    outline = AppColors.SurfaceVariant,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, content = content)
}

/** Used on every screen's TopAppBar so it blends into the background instead of reading as a
 *  separate, oddly-lighter strip — Material3's default app bar tone is a step lighter than the
 *  page background, which is exactly the seam that looked wrong. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flushTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = AppColors.Background,
    titleContentColor = AppColors.OnSurface,
    navigationIconContentColor = AppColors.OnSurface,
    actionIconContentColor = AppColors.OnSurface,
)
