package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Status
import javax.swing.AbstractListModel

/**
 * One feedback row's data.
 *
 * [stale] and [fileMissing] are resolved once during a rebuild and carried here so the renderer
 * never touches the VFS - renderers run on every repaint.
 */
data class FeedbackItemNode(
  val feedback: Feedback,
  val stale: Boolean,
  val fileMissing: Boolean,
)

/** Cached staleness for one feedback item. */
data class StaleFlags(val stale: Boolean, val fileMissing: Boolean) {
  companion object {
    val NONE = StaleFlags(stale = false, fileMissing = false)
  }
}

/** A row in the flattened queue. The renderer switches layout on the kind. */
sealed interface FeedbackRow {

  /** A collapsible status group header, e.g. "Pending  3". */
  data class StatusHeader(val status: Status, val total: Int, val collapsed: Boolean) : FeedbackRow

  /** One feedback card under its header. */
  data class Item(val node: FeedbackItemNode) : FeedbackRow
}

/**
 * Flattens the store into the rows the queue shows: for each non-empty status, in [GROUP_ORDER], a
 * [FeedbackRow.StatusHeader] followed by its [FeedbackRow.Item]s.
 *
 * A list rather than a tree because a tree row can only render a single line of text, and this panel
 * docks to a narrow strip where a note has to wrap to stay readable. What a tree gave for free -
 * collapsing a group - is the small amount of state kept here.
 *
 * Pure data transform, no painting, so it is unit-testable headless.
 */
class FeedbackListModel : AbstractListModel<FeedbackRow>() {

  private var rows: List<FeedbackRow> = emptyList()
  private var lastItems: List<Feedback> = emptyList()
  private var lastFlags: Map<String, StaleFlags> = emptyMap()

  /**
   * Finished items are kept until someone deletes them, so without this a long history would push
   * PENDING out of view - the one thing the panel exists to show.
   */
  private val collapsed: MutableSet<Status> = COLLAPSED_BY_DEFAULT.toMutableSet()

  override fun getSize(): Int = rows.size

  override fun getElementAt(index: Int): FeedbackRow = rows[index]

  fun isCollapsed(status: Status): Boolean = status in collapsed

  fun toggleCollapsed(status: Status) {
    if (!collapsed.add(status)) collapsed.remove(status)
    rebuild(lastItems, lastFlags)
  }

  fun setItems(items: List<Feedback>, flags: Map<String, StaleFlags>) = rebuild(items, flags)

  /** Every header currently in the list, in render order. */
  fun headers(): List<FeedbackRow.StatusHeader> = rows.filterIsInstance<FeedbackRow.StatusHeader>()

  private fun rebuild(items: List<Feedback>, flags: Map<String, StaleFlags>) {
    lastItems = items
    lastFlags = flags

    val newRows = mutableListOf<FeedbackRow>()
    for (status in GROUP_ORDER) {
      val group = items.asSequence()
        .filter { it.status == status }
        .sortedByDescending { it.createdAt } // newest first inside a group
        .toList()
      if (group.isEmpty()) continue // an empty header is noise, not information

      val isCollapsed = status in collapsed
      newRows += FeedbackRow.StatusHeader(status, group.size, isCollapsed)
      if (!isCollapsed) {
        group.forEach { feedback ->
          val f = flags[feedback.id] ?: StaleFlags.NONE
          newRows += FeedbackRow.Item(FeedbackItemNode(feedback, f.stale, f.fileMissing))
        }
      }
    }

    val oldSize = rows.size
    rows = newRows
    if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
    if (rows.isNotEmpty()) fireIntervalAdded(this, 0, rows.size - 1)
  }

  companion object {
    /** Render order. RESOLVED/DISMISSED sit last because they are history, not work. */
    val GROUP_ORDER = listOf(Status.PENDING, Status.ACKNOWLEDGED, Status.RESOLVED, Status.DISMISSED)

    /** Groups that start collapsed. */
    val COLLAPSED_BY_DEFAULT = setOf(Status.RESOLVED, Status.DISMISSED)

    /** Human label for a status header. */
    fun label(status: Status): String = when (status) {
      Status.PENDING -> "Pending"
      Status.ACKNOWLEDGED -> "Acknowledged"
      Status.RESOLVED -> "Resolved"
      Status.DISMISSED -> "Dismissed"
    }
  }
}
