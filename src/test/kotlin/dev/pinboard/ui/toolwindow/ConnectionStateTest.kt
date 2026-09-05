package dev.pinboard.ui.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chip's whole value is that a user can trust it, so these pin the two things that would destroy
 * that: claiming a connection that was never observed, and reporting a working setup as broken.
 */
class ConnectionStateTest {

  private val now = 1_700_000_000_000L

  @Test
  fun testNoToolsetAndNoCallsReportsTheRegistrationFailure() {
    val view = connectionView(toolsetRegistered = false, lastToolCallAt = null, lastToolName = null, now = now)

    assertEquals(ConnectionTone.ERROR, view.tone)
    assertEquals("Tools not registered", view.chipLabel)
    assertTrue("must tell the user what to do about it", view.detail.contains("restart", ignoreCase = true))
  }

  /**
   * The failure this guards is subtle: a tool call that already arrived proves the toolset is
   * registered. If a flaky read of the extension point could still paint the panel red, the user
   * would be told to restart an IDE that is working perfectly.
   */
  @Test
  fun testAnObservedCallOutweighsANegativeRegistrationCheck() {
    val view = connectionView(
      toolsetRegistered = false,
      lastToolCallAt = now - 1_000,
      lastToolName = "feedback_list",
      now = now,
    )

    assertEquals(ConnectionTone.ACTIVE, view.tone)
    assertEquals("Agent active", view.chipLabel)
  }

  @Test
  fun testUnknownRegistrationNeverClaimsAFailure() {
    val view = connectionView(toolsetRegistered = null, lastToolCallAt = null, lastToolName = null, now = now)

    assertEquals(ConnectionTone.WAITING, view.tone)
    assertEquals("Waiting for agent", view.chipLabel)
  }

  /**
   * The mirror image of the connection claim, pointed inward. When the registration check itself
   * failed, saying "tools are registered" asserts something nothing verified - the exact species of
   * unearned confidence this component exists to avoid.
   */
  @Test
  fun testUnknownRegistrationDoesNotClaimRegistrationEither() {
    val unknown = connectionView(toolsetRegistered = null, lastToolCallAt = null, lastToolName = null, now = now)
    val known = connectionView(toolsetRegistered = true, lastToolCallAt = null, lastToolName = null, now = now)

    assertEquals("No agent has called yet", unknown.detail)
    assertTrue(
      "only a verified check may say the tools are registered",
      known.detail.contains("registered") && !unknown.detail.contains("registered"),
    )
  }

  @Test
  fun testRegisteredButNeverCalledWaits() {
    val view = connectionView(toolsetRegistered = true, lastToolCallAt = null, lastToolName = null, now = now)

    assertEquals(ConnectionTone.WAITING, view.tone)
    assertTrue(view.detail.contains("no agent has called", ignoreCase = true))
  }

  @Test
  fun testRecentCallReadsActiveAndNamesTheTool() {
    val view = connectionView(
      toolsetRegistered = true,
      lastToolCallAt = now - 30_000,
      lastToolName = "feedback_watch",
      now = now,
    )

    assertEquals(ConnectionTone.ACTIVE, view.tone)
    assertTrue(view.detail.contains("feedback_watch"))
    assertTrue(view.detail.contains("30s ago"))
  }

  /**
   * An agent working one item runs tests and edits files between tool calls. A window short enough
   * to flicker during that would teach the user to ignore the chip, so the boundary is asserted.
   */
  @Test
  fun testStaysActiveAcrossANineMinuteGapAndGoesIdleAfterEleven() {
    val nineMinutes = connectionView(true, now - 9 * 60_000, "feedback_list", now)
    val elevenMinutes = connectionView(true, now - 11 * 60_000, "feedback_list", now)

    assertEquals(ConnectionTone.ACTIVE, nineMinutes.tone)
    assertEquals(ConnectionTone.IDLE, elevenMinutes.tone)
    assertEquals("Idle", elevenMinutes.chipLabel)
  }

  @Test
  fun testNoViewEverClaimsAConnection() {
    val views = listOf(
      connectionView(false, null, null, now),
      connectionView(null, null, null, now),
      connectionView(true, null, null, now),
      connectionView(true, now - 1_000, "feedback_list", now),
      connectionView(true, now - 3_600_000, "feedback_list", now),
    )

    // Pinboard rides the IDE's MCP server and cannot ask it whether a client is attached. Any
    // wording that implies it knows would be a guess presented as a fact.
    for (view in views) {
      assertTrue(
        "chip must not claim a connection: ${view.chipLabel}",
        !view.chipLabel.contains("connect", ignoreCase = true),
      )
      assertTrue(
        "detail must not claim a connection: ${view.detail}",
        !view.detail.contains("connected", ignoreCase = true),
      )
    }
  }
}
