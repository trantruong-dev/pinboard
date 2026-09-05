package dev.pinboard.ui.toolwindow

/**
 * Short age for a past timestamp, e.g. `2m ago`.
 *
 * Deliberately coarse: the panel shows when something last happened, not how long ago to the second,
 * and a coarse label stops the footer from repainting on every timer tick.
 *
 * A timestamp in the future (clock skew, a store file copied between machines) reads as `just now`
 * rather than a negative age.
 */
fun relativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
  val seconds = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
  return when {
    seconds < 5 -> "just now"
    seconds < 60 -> "${seconds}s ago"
    seconds < 3_600 -> "${seconds / 60}m ago"
    seconds < 86_400 -> "${seconds / 3_600}h ago"
    else -> "${seconds / 86_400}d ago"
  }
}
