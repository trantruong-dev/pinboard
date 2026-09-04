package dev.pinboard.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/** One agent tool call: the tool's name and the moment it arrived, inseparable. */
data class ToolCall(val name: String, val at: Long)

/**
 * What the agent has actually done through this plugin's MCP tools, and a short log of it.
 *
 * This is the only thing Pinboard can honestly say about the agent's connection. The MCP server
 * belongs to the IDE, not to this plugin, so there is no way to ask it whether a client is attached
 * or when it last spoke. A tool call arriving here is direct evidence and needs no interpretation:
 * something on the other end reached us at this moment.
 *
 * Activity and the log live in one service on purpose - they are the same fact recorded at two
 * resolutions, written on the same threads, with the same lifetime.
 *
 * Writes arrive on the MCP server's coroutine threads and reads happen on the EDT.
 */
@Service(Service.Level.PROJECT)
class McpActivity {

  /**
   * The last call, as one value.
   *
   * Name and timestamp are deliberately not two fields. `feedback_watch` blocks for up to ten
   * minutes and other tools arrive while it does, so two independent volatiles could be read
   * between one call's write of the timestamp and the next call's write of the name - and the
   * footer would confidently attribute a call to the wrong tool. For a panel whose only value is
   * that the user can trust it, a plausible wrong answer is worse than no answer.
   */
  @Volatile
  var lastCall: ToolCall? = null
    private set

  private val lock = Any()
  private val lines = ArrayDeque<String>()
  private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

  /** Records one agent tool call. Runs on every tool, before the tool does any work. */
  fun record(tool: String) {
    lastCall = ToolCall(tool, System.currentTimeMillis())
    append("$tool called")
  }

  /** Adds a free-form line, for events worth explaining that are not a bare tool call. */
  fun append(message: String) {
    val line = "${TIME.format(LocalTime.now())}  $message"
    synchronized(lock) {
      lines.addLast(line)
      // Bounded: this runs for the life of the IDE session and a watch loop can append every minute.
      while (lines.size > MAX_LINES) lines.removeFirst()
    }
    // Notified outside the lock: a listener hops to the EDT and must never be able to stall a tool
    // call that is holding an agent's request open.
    listeners.forEach { safely(it, line) }
  }

  fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

  /**
   * Replays the log into [listener] and keeps sending until [parent] is disposed.
   *
   * Subscription and snapshot happen under the same lock because doing them separately loses any
   * line recorded between the two - and that missing line would be the interesting one, since this
   * log is opened precisely when something looks wrong.
   */
  fun subscribe(parent: Disposable, listener: (String) -> Unit) {
    val backlog = synchronized(lock) {
      listeners.add(listener)
      lines.toList()
    }
    Disposer.register(parent) { listeners.remove(listener) }
    backlog.forEach { safely(listener, it) }
  }

  /**
   * A listener that throws must not become an agent-visible tool failure. [record] runs before the
   * tool does its work, so without this a UI bug in the footer would surface to the agent as the
   * feedback tool itself failing - telemetry breaking the operation it exists to observe.
   */
  private fun safely(listener: (String) -> Unit, line: String) {
    try {
      listener(line)
    } catch (t: Throwable) {
      LOG.debug("An activity listener failed", t)
    }
  }

  companion object {
    private const val MAX_LINES = 200
    private val LOG = logger<McpActivity>()
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun getInstance(project: Project): McpActivity = project.getService(McpActivity::class.java)
  }
}
