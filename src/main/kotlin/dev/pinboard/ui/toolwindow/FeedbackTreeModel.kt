package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Status
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Payloads placed in [DefaultMutableTreeNode.userObject]. */
sealed interface FeedbackNode

/** A status header row, e.g. "Pending  3". */
data class StatusGroupNode(val status: Status, val count: Int) : FeedbackNode

/**
 * One feedback row. [stale] and [fileMissing] are resolved once during the rebuild and carried
 * here so the cell renderer never touches the VFS - renderers run on every repaint.
 */
data class FeedbackItemNode(
  val feedback: Feedback,
  val stale: Boolean,
  val fileMissing: Boolean,
) : FeedbackNode

/** Cached staleness for one feedback item. */
data class StaleFlags(val stale: Boolean, val fileMissing: Boolean) {
  companion object {
    val NONE = StaleFlags(stale = false, fileMissing = false)
  }
}

/**
 * Builds the grouped tree shown in the ToolWindow.
 *
 * Groups are ordered so work still owed to the agent stays on top, and empty groups are hidden
 * rather than rendered as empty headers.
 */
object FeedbackTreeModel {

  /** Render order. RESOLVED/DISMISSED sit last because they are history, not work. */
  val GROUP_ORDER = listOf(Status.PENDING, Status.ACKNOWLEDGED, Status.RESOLVED, Status.DISMISSED)

  /**
   * Groups that start collapsed. Finished items are kept forever (the user deletes them, or the
   * agent clears them via `feedback_clear_resolved`), so without this a long history would push
   * PENDING out of view - which is the one thing the panel exists to show.
   */
  val COLLAPSED_BY_DEFAULT = setOf(Status.RESOLVED, Status.DISMISSED)

  fun build(items: List<Feedback>, stale: Map<String, StaleFlags>): DefaultTreeModel {
    val root = DefaultMutableTreeNode("root")
    for (status in GROUP_ORDER) {
      val group = items.asSequence()
        .filter { it.status == status }
        .sortedByDescending { it.createdAt }  // newest first inside a group
        .toList()
      if (group.isEmpty()) continue
      val groupNode = DefaultMutableTreeNode(StatusGroupNode(status, group.size))
      for (feedback in group) {
        val flags = stale[feedback.id] ?: StaleFlags.NONE
        groupNode.add(DefaultMutableTreeNode(FeedbackItemNode(feedback, flags.stale, flags.fileMissing)))
      }
      root.add(groupNode)
    }
    return DefaultTreeModel(root)
  }

  /** Human label for a status header. */
  fun label(status: Status): String = when (status) {
    Status.PENDING -> "Pending"
    Status.ACKNOWLEDGED -> "Acknowledged"
    Status.RESOLVED -> "Resolved"
    Status.DISMISSED -> "Dismissed"
  }
}
