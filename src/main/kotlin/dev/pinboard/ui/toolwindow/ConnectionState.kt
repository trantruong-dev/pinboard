package dev.pinboard.ui.toolwindow

import com.intellij.ui.JBColor
import dev.pinboard.ui.theme.PinboardColors

/** How much weight the connection chip carries. */
enum class ConnectionTone {
  /** The agent called a tool recently. */
  ACTIVE,

  /** No agent has called yet. */
  WAITING,

  /** An agent has called before, but not lately. Normal between tasks, not a problem. */
  IDLE,

  /** The user has to do something. */
  ERROR,
  ;

  val color: JBColor
    get() = when (this) {
      ACTIVE -> PinboardColors.statusActive
      WAITING -> PinboardColors.statusPending
      IDLE -> PinboardColors.textMuted
      ERROR -> PinboardColors.statusError
    }
}

/** What the chip shows and what its tooltip and the footer spell out. */
data class ConnectionView(val chipLabel: String, val tone: ConnectionTone, val detail: String)

/**
 * Turns what the plugin knows into what it is allowed to say. Pure, so the wording and the
 * thresholds are testable without an IDE.
 *
 * The chip never says "connected". Pinboard rides the IDE's MCP server and cannot ask it whether a
 * client is attached, so a connected/disconnected claim would be a guess dressed up as a fact. What
 * it says instead is only ever derived from calls that actually arrived.
 *
 * @param toolsetRegistered `null` means the check could not be run - see [dev.pinboard.mcp.ToolsetHealth].
 */
fun connectionView(
  toolsetRegistered: Boolean?,
  lastToolCallAt: Long?,
  lastToolName: String?,
  now: Long = System.currentTimeMillis(),
): ConnectionView {
  if (lastToolCallAt == null) {
    return when (toolsetRegistered) {
      // A registration that demonstrably did not happen is the one failure worth interrupting for:
      // every tool call will fail until the IDE restarts, and nothing else in the UI shows it.
      false -> ConnectionView(
        chipLabel = "Tools not registered",
        tone = ConnectionTone.ERROR,
        detail = "Pinboard loaded without its MCP tools - restart the IDE",
      )

      true -> ConnectionView(
        chipLabel = "Waiting for agent",
        tone = ConnectionTone.WAITING,
        detail = "Tools are registered - no agent has called them yet",
      )

      // The check failed, so registration is unknown. Saying "tools are registered" here would be
      // the same unearned assertion this whole component exists to avoid, just pointed inward
      // instead of at the connection. Report only the part that is observed: nothing has called.
      null -> ConnectionView(
        chipLabel = "Waiting for agent",
        tone = ConnectionTone.WAITING,
        detail = "No agent has called yet",
      )
    }
  }

  // A call that arrived is proof the toolset is registered, whatever the check reports. Evidence
  // beats introspection, so a flaky read of the extension point can never paint a working setup red.
  val age = relativeTime(lastToolCallAt, now)
  val tool = lastToolName?.let { " · $it" } ?: ""
  return if (now - lastToolCallAt <= ACTIVE_WINDOW_MS) {
    ConnectionView("Agent active", ConnectionTone.ACTIVE, "Last call $age$tool")
  } else {
    ConnectionView("Idle", ConnectionTone.IDLE, "Last call $age$tool")
  }
}

/**
 * How recent a call has to be for the agent to read as active.
 *
 * Ten minutes, not one: an agent working a single item runs tests and edits files between tool
 * calls, and a chip that flickered to Idle in those gaps would train the user to ignore it.
 */
private const val ACTIVE_WINDOW_MS = 10 * 60 * 1000L
