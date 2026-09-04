package dev.pinboard.ui.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

  private val now = 1_700_000_000_000L

  private fun ago(seconds: Long) = relativeTime(now - seconds * 1000, now)

  @Test
  fun testUnitBoundaries() {
    assertEquals("just now", ago(0))
    assertEquals("just now", ago(4))
    assertEquals("5s ago", ago(5))
    assertEquals("59s ago", ago(59))
    assertEquals("1m ago", ago(60))
    assertEquals("59m ago", ago(3_599))
    assertEquals("1h ago", ago(3_600))
    assertEquals("23h ago", ago(86_399))
    assertEquals("1d ago", ago(86_400))
  }

  /**
   * A store file copied between machines, or a corrected clock, can leave a timestamp in the future.
   * That must read as "just now" rather than a negative age.
   */
  @Test
  fun testFutureTimestampDoesNotGoNegative() {
    assertEquals("just now", relativeTime(now + 60_000, now))
  }
}
