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
    // Brightened from an original 0x8B95A1 — muted text/icons (dim toggle-tile state, secondary
    // labels) were losing too much contrast in bright/outdoor light against the near-black
    // background above.
    @Suppress("MagicNumber") // color literal — see the class doc's note on why only lines changed
    // this pass trip this rule; every other color constant in this object is the same shape.
    private val DarkOnSurfaceMuted = Color(0xFFA3ADB8)

    // Color literals below, same reasoning as DarkOnSurfaceMuted above — this whole light-mode
    // palette is new this pass, so none of it is baseline-grandfathered like the dark one above.
    @Suppress("MagicNumber") private val LightBackground = Color(0xFFF5F6F8)
    @Suppress("MagicNumber") private val LightSurface = Color(0xFFFFFFFF)
    @Suppress("MagicNumber") private val LightSurfaceVariant = Color(0xFFE7EAEE)
    @Suppress("MagicNumber") private val LightOnSurface = Color(0xFF14181C)
    @Suppress("MagicNumber") private val LightOnSurfaceMuted = Color(0xFF5B6470)

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
    val scheme = if (AppColors.isDarkMode) {
        darkColorScheme(
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
    } else {
        lightColorScheme(
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
    }
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
