package org.offlinemesh.app.ui

private const val MS_PER_HOUR = 60 * 60 * 1000L
private const val HOURS_PER_DAY = 24L

/**
 * Timed groups default to 48 hours. Null millis is an explicit permanent-group choice.
 * Finite lifetimes remain bounded by [org.offlinemesh.app.data.JoinCode.decode].
 */
data class GroupLifetimeOption(val label: String, val shortLabel: String, val millis: Long?)

@Suppress("MagicNumber") // each number here IS the meaningful quantity (hours/days per option) —
// naming them individually would just rename "12" to "TWELVE", not add real information. MS_PER_HOUR/
// HOURS_PER_DAY above already factor out the actual repeated unit-conversion magic numbers.
val GROUP_LIFETIME_OPTIONS = listOf(
    GroupLifetimeOption("12 hours", "12h", 12 * MS_PER_HOUR),
    GroupLifetimeOption("48 hours", "48h", 48 * MS_PER_HOUR), // default
    GroupLifetimeOption("72 hours", "72h", 72 * MS_PER_HOUR),
    GroupLifetimeOption("7 days", "7d", 7 * HOURS_PER_DAY * MS_PER_HOUR),
    GroupLifetimeOption("30 days", "30d", 30 * HOURS_PER_DAY * MS_PER_HOUR),
    GroupLifetimeOption("6 months", "6mo", 180 * HOURS_PER_DAY * MS_PER_HOUR),
    GroupLifetimeOption("No expiry", "No expiry", null),
)

const val DEFAULT_GROUP_LIFETIME_INDEX = 1 // 48 hours

/** Compact "how much longer" string — `"41h"` / `"2d"` — shared by the join-preview text
 *  ([org.offlinemesh.app.ui.AddGroupScreen]) and the per-group remaining-time label
 *  ([org.offlinemesh.app.ui.HomeScreen]'s group row). Deliberately coarse (whole hours or whole
 *  days), not exact-to-the-minute — this is a rough sense of how long is left, not a countdown. */
fun formatTimeRemaining(millis: Long): String {
    if (millis <= 0) return "expired"
    val hours = millis / MS_PER_HOUR
    return when {
        hours < 1 -> "<1h"
        hours < 2 * HOURS_PER_DAY -> "${hours}h"
        else -> "${hours / HOURS_PER_DAY}d"
    }
}
