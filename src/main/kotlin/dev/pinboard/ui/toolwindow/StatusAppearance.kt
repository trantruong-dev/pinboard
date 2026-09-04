package dev.pinboard.ui.toolwindow

import com.intellij.ui.JBColor
import dev.pinboard.model.Status
import dev.pinboard.ui.theme.PinboardColors

/**
 * The single mapping from an item's state to how it looks. The card, the progress ribbon, the editor
 * gutter and the tool window badge all read it, so a status cannot mean amber in one place and green
 * in another.
 */
object StatusAppearance {

  fun label(status: Status): String = FeedbackListModel.label(status)

  fun color(status: Status): JBColor = when (status) {
    Status.PENDING -> PinboardColors.statusPending
    Status.ACKNOWLEDGED -> PinboardColors.statusActive
    Status.RESOLVED, Status.DISMISSED -> PinboardColors.statusDone
  }

  /** Items the agent still owes work on. Drives the badge, the ribbon and the editor decorations. */
  fun isOpen(status: Status): Boolean = status == Status.PENDING || status == Status.ACKNOWLEDGED

  /**
   * A short warning to show beside the status, or `null`.
   *
   * Only open items warn. A resolved item pointing at code that has since changed is the expected
   * outcome of the agent doing the work - flagging it would make every finished item look broken.
   */
  fun warning(node: FeedbackItemNode): String? = when {
    !isOpen(node.feedback.status) -> null
    node.fileMissing -> "file missing"
    node.stale -> "stale"
    else -> null
  }
}
