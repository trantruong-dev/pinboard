package dev.pinboard.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Answers one question: did [FeedbackToolset] actually reach the IDE's MCP server?
 *
 * This exists because of a failure that is invisible without it. When the plugin is loaded without
 * a restart, the tool window comes up and works, the queue fills, everything looks healthy - and the
 * `mcpToolset` extension is silently absent, so the agent gets `Tool not found: feedback_list` and
 * the user has no way to connect the two. `require-restart="true"` in plugin.xml prevents the load
 * path that causes it; this check is what makes the state visible if it happens anyway.
 *
 * Note there is no check for the MCP Server plugin being present at all: plugin.xml declares a hard
 * `<depends>com.intellij.mcpServer</depends>`, so if it were missing or disabled the platform would
 * not load Pinboard either and none of this code would run.
 */
object ToolsetHealth {

  private val LOG = logger<ToolsetHealth>()

  /** Declared by the MCP Server plugin as `<extensionPoint name="mcpToolset">` under its own id. */
  private val EP = ExtensionPointName<McpToolset>("com.intellij.mcpServer.mcpToolset")

  /**
   * Cached because the answer cannot change within a session: plugin.xml sets
   * `require-restart="true"`, so the set of registered toolsets is fixed once the IDE is up.
   * Reading the extension point can instantiate every registered toolset, which is not something to
   * repeat on the EDT every couple of seconds for a value that is already known.
   *
   * Only a definite answer is cached. A failed read must stay retryable, or one unlucky call during
   * startup would freeze "unknown" in for the rest of the session.
   */
  @Volatile
  private var cached: Boolean? = null

  /**
   * `true` when our toolset is registered, `false` when it demonstrably is not, and `null` when the
   * extension point could not be read.
   *
   * The `null` case matters: callers must be able to tell "we know it is broken" from "we could not
   * find out", because showing a red error the plugin cannot actually stand behind is worse than
   * showing nothing.
   */
  fun feedbackToolsetRegistered(): Boolean? {
    cached?.let { return it }
    val answer = read()
    if (answer != null) cached = answer
    return answer
  }

  private fun read(): Boolean? =
    try {
      EP.extensionList.any { it is FeedbackToolset }
    } catch (e: ProcessCanceledException) {
      // Never swallowed: the platform uses it to unwind work and treating it as a failed read would
      // both lie about the toolset and break cancellation.
      throw e
    } catch (t: Throwable) {
      // A missing or not-yet-initialised extension point throws rather than returning empty.
      LOG.debug("Could not read the mcpToolset extension point", t)
      null
    }
}
