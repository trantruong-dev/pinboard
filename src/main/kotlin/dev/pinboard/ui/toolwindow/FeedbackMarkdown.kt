package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Scope
import dev.pinboard.ui.locationLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders one pinned item as the Markdown you would paste into an agent.
 *
 * Selecting text in the detail panel answers "copy this line". It cannot answer "here, take this
 * whole pin": the location, the note, the snapshot and the conversation live in four separate Swing
 * components and no drag spans them. This is that gesture's output.
 *
 * A separate object rather than a method on the panel because it is pure - no project, no platform
 * service, no running application - which is what lets [FeedbackMarkdownTest] assert every edge
 * case as a plain JUnit test with no IDE fixture behind it.
 */
object FeedbackMarkdown {

  /**
   * The whole item, ready to paste. Body text is emitted verbatim: the store keeps it raw, and
   * Markdown is the output format here, so there is nothing to escape into.
   */
  fun render(node: FeedbackItemNode): String {
    val feedback = node.feedback
    val sections = mutableListOf<String>()

    sections += "### ${feedback.locationLabel()} - ${FeedbackListModel.label(feedback.status)}\n${meta(node)}"
    if (feedback.note.isNotBlank()) sections += "**Note**\n${feedback.note}"
    codeBlock(feedback)?.let { sections += "**Code at capture time**\n$it" }
    if (feedback.thread.isNotEmpty()) {
      sections += "**Conversation**\n" + feedback.thread.joinToString("\n") { line(it) }
    }

    return sections.joinToString("\n\n")
  }

  /**
   * Several items in one paste, for handing the batch to an agent that cannot read the queue over
   * MCP. That handover is the gesture the queue was built for, and copying pins one at a time is
   * not it.
   *
   * Empty in, empty out. Whether an empty clipboard is worth writing is the caller's decision, not
   * this object's.
   */
  fun renderAll(nodes: List<FeedbackItemNode>): String {
    if (nodes.isEmpty()) return ""
    // Each item opens with `###`, so a `##` line above them reads as what contains them, and the
    // rule between two items is what stops them running together in a Markdown viewer.
    val preamble = "## ${nodes.size} pinned ${if (nodes.size == 1) "item" else "items"}"
    return preamble + "\n\n" + nodes.joinToString(separator = "\n\n---\n\n") { render(it) }
  }

  /** A missing file outranks a stale one: it is the stronger statement about the same doubt. */
  private fun meta(node: FeedbackItemNode): String {
    val suffix = when {
      node.fileMissing -> " - file no longer exists"
      node.stale -> " - stale: code changed since pinned"
      else -> ""
    }
    return "Pinned ${timestamp(node.feedback.createdAt)}$suffix"
  }

  private fun codeBlock(feedback: Feedback): String? {
    // Project scope has no lines to show, so a snapshot on one is not the pinned code.
    if (feedback.scope == Scope.PROJECT) return null
    val code = feedback.codeSnapshot ?: return null
    val fence = "`".repeat(fenceLength(code))
    return "$fence${feedback.language?.lowercase() ?: ""}\n$code\n$fence"
  }

  /** Long enough to outrun any fence the snapshot itself contains, which would end the block early. */
  private fun fenceLength(code: String): Int {
    var longest = 0
    var run = 0
    for (ch in code) {
      if (ch == '`') {
        run++
        if (run > longest) longest = run
      } else {
        run = 0
      }
    }
    return maxOf(3, longest + 1)
  }

  private fun line(message: Message): String {
    val who = if (message.author == Author.HUMAN) "You" else "Agent"
    return "- $who (${timestamp(message.createdAt)}): ${message.body}"
  }

  /**
   * Plain [java.time] rather than the platform's DateFormatUtil, which needs a running application
   * and would drag this object - and its test - back into a fixture.
   */
  private fun timestamp(epochMillis: Long): String =
    FORMATTER.format(Instant.ofEpochMilli(epochMillis))

  private val FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
}
