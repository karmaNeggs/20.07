package org.offlinemesh.app.ui

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Dark by default, low-glare, and deliberately mostly monochrome — color is spent on meaning, not
 * decoration. Three reserved colors, never used for anything else: green = presence/safe,
 * red = SOS/danger, and a soft indigo for generic interactive accents (the default "this is
 * tappable" color when nothing more specific applies). Group identity gets its own separate
 * palette so it's never confused with any of the above — all four hold the same values in both
 * light and dark mode, already saturated enough to read against either a near-black or a white
 * surface.
 *
 * Fields below are computed properties over [isDark], not plain constants — reading e.g.
 * [Background] inside a `@Composable` subscribes to Compose's snapshot system via the backing
 * [mutableStateOf], so every existing call site across the app (there are dozens, all written as
 * plain `AppColors.X` reads) recomposes correctly the moment the theme toggles, with no changes
 * needed at any of them. That's the whole point of this shape over a `ColorScheme`-only switch.
 */
object AppColors {
    private var isDark by mutableStateOf(true)
    val isDarkMode: Boolean get() = isDark

    val Background: Color get() = if (isDark) DarkBackground else LightBackground
    val Surface: Color get() = if (isDark) DarkSurface else LightSurface
    val SurfaceVariant: Color get() = if (isDark) DarkSurfaceVariant else LightSurfaceVariant
    val OnSurface: Color get() = if (isDark) DarkOnSurface else LightOnSurface
    val OnSurfaceMuted: Color get() = if (isDark) DarkOnSurfaceMuted else LightOnSurfaceMuted

    private val DarkBackground = Color(0xFF0A0D10)
    private val DarkSurface = Color(0xFF14181C)
    private val DarkSurfaceVariant = Color(0xFF1C2126)
    private val DarkOnSurface = Color(0xFFEDEFF1)
    // Brightened twice now: 0x8B95A1 -> 0xA3ADB8 (first pass) -> this. Live-tested finding
    // (2026-08-06, hardware test round 2, decision 30): still read as too dim/gray against direct
    // sun compared to DarkOnSurface's near-white — muted text/icons need to sit noticeably closer
    // to white, not just a step up from the original, to hold contrast outdoors.
    @Suppress("MagicNumber") // color literal — see the class doc's note on why only lines changed
    // this pass trip this rule; every other color constant in this object is the same shape.
    private val DarkOnSurfaceMuted = Color(0xFFC3CCD4)

    // Color literals below, same reasoning as DarkOnSurfaceMuted above — this whole light-mode
    // palette is new this pass, so none of it is baseline-grandfathered like the dark one above.
    @Suppress("MagicNumber") private val LightBackground = Color(0xFFF5F6F8)
    @Suppress("MagicNumber") private val LightSurface = Color(0xFFFFFFFF)
    @Suppress("MagicNumber") private val LightSurfaceVariant = Color(0xFFE7EAEE)
    @Suppress("MagicNumber") private val LightOnSurface = Color(0xFF14181C)
    @Suppress("MagicNumber") private val LightOnSurfaceMuted = Color(0xFF5B6470)

    // Brightened from 0x34D399 (2026-08-06, hardware test round 2, decision 30) — live feedback
    // was "more green, more luminous" specifically on the radar, where this is the only color in
    // play; a single shared constant means the same boost also lifts every other presence/"safe"
    // use of it (toggle tiles, connection dots) rather than forking a radar-only shade.
    // Light mode (2026-10-14 colour scan): the dark-mode shades measured 1.4-2.8:1 against the light
    // background (below the 3:1 floor for icons and text), so light mode uses deeper shades of the same hues.
    val Safe: Color get() = if (isDark) SafeDark else SafeLight           // presence / connected / in range
    val Danger: Color get() = if (isDark) DangerDark else DangerLight     // SOS only — nothing else
    val Warning: Color get() = if (isDark) WarningDark else WarningLight  // low-confidence / degraded warnings
    val Accent: Color get() = if (isDark) AccentDark else AccentLight     // generic interactive accent

    /** Text and icons placed ON an [Accent] fill (buttons, selected segments): white on the pale dark-mode
     *  indigo measured 3.0:1, so dark mode uses near-black (6.5:1); light mode's deeper indigo takes white. */
    val OnAccent: Color get() = if (isDark) DarkBackground else Color.White

    /** Border of text fields and outlined buttons; the old value (the surface tone) was nearly invisible (1.2:1). */
    val Outline: Color get() = if (isDark) DarkOutline else LightOutline

    @Suppress("MagicNumber") private val DarkOutline = Color(0xFF4A545E)
    @Suppress("MagicNumber") private val LightOutline = Color(0xFF8A94A0)
    @Suppress("MagicNumber") private val SafeDark = Color(0xFF6EE7B7)
    @Suppress("MagicNumber") private val SafeLight = Color(0xFF047857)
    @Suppress("MagicNumber") private val DangerDark = Color(0xFFEF4444)
    @Suppress("MagicNumber") private val DangerLight = Color(0xFFDC2626)
    @Suppress("MagicNumber") private val WarningDark = Color(0xFFF59E0B)
    @Suppress("MagicNumber") private val WarningLight = Color(0xFFB45309)
    @Suppress("MagicNumber") private val AccentDark = Color(0xFF818CF8)
    @Suppress("MagicNumber") private val AccentLight = Color(0xFF4F46E5)

    /** Amber fill of the SOS button: fixed in both modes, black text on it measures 9:1. */
    @Suppress("MagicNumber") val SosButton = Color(0xFFF59E0B)

    /** Text and icons placed on a group-colour fill: every palette colour is pale, so near-black (7:1 or better). */
    val OnGroupColor: Color get() = DarkBackground

    /** A group colour used as TEXT: unchanged in dark mode, darkened in light mode where the pale palette vanishes. */
    fun groupTextColor(c: Color): Color =
        if (isDark) c else androidx.compose.ui.graphics.lerp(c, Color.Black, GROUP_TEXT_DARKEN)

    private const val GROUP_TEXT_DARKEN = 0.45f

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

    /** Called once from [AppTheme] on first composition — reads the persisted choice, defaulting
     *  to dark (this app's original, still-primary experience) if none was ever saved. */
    fun loadPersisted(context: Context) {
        isDark = context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(THEME_PREF_KEY, true)
    }

    /** Flips the live theme and persists the choice — same `"mesh_device"` prefs file every other
     *  per-install-but-user-changeable setting in this app already uses. */
    fun setDarkMode(context: Context, dark: Boolean) {
        isDark = dark
        context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(THEME_PREF_KEY, dark).apply()
    }

    private const val THEME_PREFS_NAME = "mesh_device"
    private const val THEME_PREF_KEY = "dark_theme"
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppColors.loadPersisted(context) }
    // One shared set of overrides on top of Material's dark or light base, so both modes always get the same
    // roles filled in (an unset role falls back to Material's lavender defaults, which showed up in dialogs).
    val base = if (AppColors.isDarkMode) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = AppColors.Accent,
        onPrimary = AppColors.OnAccent,
        error = AppColors.Danger,
        onError = Color.White,
        background = AppColors.Background,
        onBackground = AppColors.OnSurface,
        surface = AppColors.Surface,
        onSurface = AppColors.OnSurface,
        surfaceVariant = AppColors.SurfaceVariant,
        onSurfaceVariant = AppColors.OnSurfaceMuted,
        secondary = AppColors.Accent,
        onSecondary = AppColors.OnAccent,
        outline = AppColors.Outline,
        outlineVariant = AppColors.SurfaceVariant,
        primaryContainer = AppColors.SurfaceVariant,
        onPrimaryContainer = AppColors.OnSurface,
        secondaryContainer = AppColors.SurfaceVariant,
        onSecondaryContainer = AppColors.OnSurface,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = AppColors.Background,
        surfaceContainerLow = AppColors.Surface,
        surfaceContainer = AppColors.Surface,
        surfaceContainerHigh = AppColors.SurfaceVariant,
        surfaceContainerHighest = AppColors.SurfaceVariant,
    )
    MaterialTheme(colorScheme = scheme, content = content)
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
