package dev.pinboard.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.Project
import dev.pinboard.model.Author
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay

/**
 * All 7 `feedback_*` MCP tools exposed to the agent.
 *
 * Permission boundary: the agent can list, watch, acknowledge, resolve, dismiss, reply, and clear
 * RESOLVED/DISMISSED items - but CANNOT create feedback and CANNOT delete PENDING/ACKNOWLEDGED
 * items. See the store's `deleteResolved()` guard.
 *
 * Every tool runs in the MCP server's coroutine scope (not the EDT). The project is resolved from
 * the coroutine context through [McpProjectResolver] (which bridges the 2025.2 API rename).
 */
class FeedbackToolset : McpToolset {

  private suspend fun currentProject(): Project {
    val project = McpProjectResolver.currentProject(coroutineContext)
    if (project == null || project.isDisposed) {
      throw IllegalStateException("No active project for this feedback tool call")
    }
    return project
  }

  @McpTool(name = "feedback_list")
  @McpDescription(
    "Lists pending feedback items for the current project. By default returns PENDING + " +
      "ACKNOWLEDGED items (ACKNOWLEDGED means the agent has seen it but may not have finished it - " +
      "do not treat acknowledged as done). Each item carries its own status field, so a resumed agent " +
      "can tell the two apart. RESOLVED and DISMISSED are hidden unless status is given. " +
      "Items include a stale flag when the code has changed since capture; use the original codeSnapshot " +
      "as the source of truth and symbolPath to relocate."
  )
  suspend fun feedback_list(
    status: String? = null,
    limit: Int = 20,
  ): ListResult {
    val project = currentProject()
    val store = FeedbackStore.getInstance(project)
    val requested = status?.let { parseStatus(it) }
    val all = store.all()
    val filtered = when {
      requested != null -> all.filter { it.status == requested }
      else -> all.filter { it.status == Status.PENDING || it.status == Status.ACKNOWLEDGED }
    }
    val sorted = filtered.sortedBy { it.createdAt }.take(limit.coerceAtLeast(1))
    val totalPending = all.count { it.status == Status.PENDING }
    val items = sorted.map { feedback ->
      val stale = StaleDetector.check(project, feedback)
      FeedbackItemDtoMapper.toDto(feedback, stale = stale.stale, fileMissing = stale.fileMissing)
    }
    return ListResult(items = items, totalPending = totalPending)
  }

  @McpTool(name = "feedback_acknowledge")
  @McpDescription("Marks PENDING feedback items as acknowledged so the human knows you have seen them. " +
    "Accepts an array of ids to acknowledge a whole batch in one call. Unknown ids and non-PENDING " +
    "items (RESOLVED/DISMISSED) are ignored - a resolved item is never resurrected.")
  suspend fun feedback_acknowledge(ids: List<String>): BatchResult {
    val store = FeedbackStore.getInstance(currentProject())
    var affected = 0
    for (id in ids) {
      val current = store.byId(id) ?: continue
      // Only PENDING may be acknowledged; never flip RESOLVED/DISMISSED back into the active queue.
      if (current.status == Status.PENDING) {
        store.updateStatus(id, Status.ACKNOWLEDGED)
        affected++
      }
    }
    return BatchResult(affected = affected)
  }

  @McpTool(name = "feedback_resolve")
  @McpDescription("Marks a feedback item as resolved with a required summary of what you did. " +
    "The summary is stored in the thread so the human can review it.")
  suspend fun feedback_resolve(id: String, summary: String): String {
    if (summary.isBlank()) ToolResponses.error("summary is required")
    val store = FeedbackStore.getInstance(currentProject())
    store.byId(id) ?: ToolResponses.error("No feedback with id $id")
    store.updateStatus(id, Status.RESOLVED)
    store.appendMessage(id, Author.AGENT, summary)
    return "[resolved] $id"
  }

  @McpTool(name = "feedback_dismiss")
  @McpDescription("Decides not to act on a feedback item. A required reason is stored in the thread.")
  suspend fun feedback_dismiss(id: String, reason: String): String {
    if (reason.isBlank()) ToolResponses.error("reason is required")
    val store = FeedbackStore.getInstance(currentProject())
    store.byId(id) ?: ToolResponses.error("No feedback with id $id")
    store.updateStatus(id, Status.DISMISSED)
    store.appendMessage(id, Author.AGENT, reason)
    return "[dismissed] $id"
  }

  @McpTool(name = "feedback_reply")
  @McpDescription("Adds a message to a feedback item's thread to ask the human a question. " +
    "Does not change the item's status.")
  suspend fun feedback_reply(id: String, message: String): String {
    if (message.isBlank()) ToolResponses.error("message is required")
    val store = FeedbackStore.getInstance(currentProject())
    store.byId(id) ?: ToolResponses.error("No feedback with id $id")
    store.appendMessage(id, Author.AGENT, message)
    return "[replied] $id"
  }

  @McpTool(name = "feedback_clear_resolved")
  @McpDescription(
    "Deletes feedback items that are already RESOLVED or DISMISSED, to tidy the queue. " +
      "Only call this when the human explicitly asks you to clean up - never automatically after " +
      "each batch, so resolved summaries stay visible for the human to review. " +
      "PENDING and ACKNOWLEDGED items are NEVER deleted."
  )
  suspend fun feedback_clear_resolved(): ClearResult {
    val deleted = FeedbackStore.getInstance(currentProject()).deleteResolved()
    return ClearResult(deleted = deleted)
  }

  @McpTool(name = "feedback_watch")
  @McpDescription(
    "Blocks until new feedback items appear (or the timeout elapses), then returns them in a batch. " +
      "Use this to pick up feedback without polling: call it in a loop, since it is meant to return " +
      "empty and be called again. After the first new item arrives, waits up to batchWindowSeconds to " +
      "collect a cluster. Keep timeoutSeconds well under your own tool-call timeout - the IDE will " +
      "happily block for minutes, but the MCP client gives up first and you lose the call."
  )
  suspend fun feedback_watch(
    // 60s, not 120s: measured against a real Claude Code client, a 150s call is cut off by the
    // client while the IDE is still happily blocking, and 120s sits right on that boundary.
    timeoutSeconds: Int = 60,
    batchWindowSeconds: Int = 5,
  ): ListResult {
    val project = currentProject()
    val store = FeedbackStore.getInstance(project)
    val initialIds = store.all().map { it.id }.toSet()
    val timeout = timeoutSeconds.coerceIn(1, 600)
    val batchWindow = batchWindowSeconds.coerceIn(1, 60)
    val deadline = System.currentTimeMillis() + timeout * 1000L
    var firstNew: List<String> = emptyList()

    // Poll the store at a modest interval (the platform has no push hook into MCP tool contexts,
    // so this is the documented approach after the phase-00 spike).
    while (System.currentTimeMillis() < deadline) {
      val newIds = store.all().filter { it.id !in initialIds }.map { it.id }
      if (newIds.isNotEmpty()) {
        firstNew = newIds
        break
      }
      delay(500)
    }

    if (firstNew.isEmpty()) {
      return ListResult(items = emptyList(), totalPending = store.all().count { it.status == Status.PENDING })
    }

    // Collect any further arrivals within the batch window.
    delay(batchWindow * 1000L)
    val collected = store.all().filter { it.id !in initialIds }
    val items = collected.sortedBy { it.createdAt }.map { feedback ->
      val stale = StaleDetector.check(project, feedback)
      FeedbackItemDtoMapper.toDto(feedback, stale = stale.stale, fileMissing = stale.fileMissing)
    }
    return ListResult(items = items, totalPending = store.all().count { it.status == Status.PENDING })
  }

  private fun parseStatus(raw: String): Status {
    return try {
      Status.valueOf(raw.uppercase())
    } catch (_: IllegalArgumentException) {
      ToolResponses.error("Invalid status '$raw'. Valid values: ${Status.entries.joinToString(", ")}")
    }
  }
}