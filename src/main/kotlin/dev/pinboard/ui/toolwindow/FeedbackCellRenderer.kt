package dev.pinboard.ui.toolwindow

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.pinboard.model.Scope
import dev.pinboard.util.FilePaths
import dev.pinboard.model.Status
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Renders one tree row. Must stay cheap: this runs on every repaint, so staleness is read from
 * the pre-computed [FeedbackItemNode] flags rather than hitting the VFS here.
 */
class FeedbackCellRenderer : ColoredTreeCellRenderer() {

  override fun customizeCellRenderer(
    tree: JTree,
    value: Any?,
    selected: Boolean,
    expanded: Boolean,
    leaf: Boolean,
    row: Int,
    hasFocus: Boolean,
  ) {
    when (val payload = (value as? DefaultMutableTreeNode)?.userObject) {
      is StatusGroupNode -> renderGroup(payload)
      is FeedbackItemNode -> renderItem(payload)
    }
  }

  private fun renderGroup(node: StatusGroupNode) {
    append(FeedbackTreeModel.label(node.status), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    append("  ${node.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
  }

  private fun renderItem(node: FeedbackItemNode) {
    val feedback = node.feedback
    val finished = feedback.status == Status.RESOLVED || feedback.status == Status.DISMISSED
    val noteStyle =
      if (finished) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES

    append(summarize(feedback.note), noteStyle)

    val location = location(node)
    if (location != null) {
      append("  $location", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
    }

    // Only warn about staleness while the item still needs work - a resolved item pointing at
    // changed code is expected, not a problem.
    if (!finished && node.fileMissing) {
      append("  file missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
    } else if (!finished && node.stale) {
      append("  stale", SimpleTextAttributes.ERROR_ATTRIBUTES)
    }

    // The tool window docks right and is usually narrow, so rows get clipped. The tooltip keeps
    // the full note and path reachable instead of simply lost.
    toolTipText = buildString {
      append(feedback.note.trim())
      feedback.filePath?.let { append(System.lineSeparator()).append(it) }
    }
  }

  /** First line of the note, capped so long feedback does not blow out a narrow panel. */
  private fun summarize(note: String): String {
    val firstLine = note.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: note.trim()
    val collapsed = if (firstLine.isEmpty()) "(empty note)" else firstLine
    return if (collapsed.length <= MAX_NOTE) collapsed else collapsed.take(MAX_NOTE - 1) + "…"
  }

  /** `Foo.kt:40-52` for selections, `Foo.kt` for whole files, nothing for project scope. */
  private fun location(node: FeedbackItemNode): String? {
    val feedback = node.feedback
    if (feedback.scope == Scope.PROJECT) return null
    val path = feedback.filePath ?: return null
    val fileName = fileName(path)
    val start = feedback.startLine ?: return fileName
    val end = feedback.endLine
    return if (end != null && end != start) "$fileName:$start-$end" else "$fileName:$start"
  }

  companion object {
    private const val MAX_NOTE = 60

    /** Delegates so rows and path resolution can never disagree about what a file is called. */
    internal fun fileName(path: String): String = FilePaths.fileName(path)
  }
}
