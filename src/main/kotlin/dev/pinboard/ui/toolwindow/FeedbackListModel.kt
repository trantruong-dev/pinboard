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

  /**
   * What the last rebuild was given, kept because it is folding-independent where [rows] is not.
   * [pendingNodes] is built from this, so it is read by actions and not only by a rebuild.
   *
   * Not volatile, and must stay that way round: rebuilds are computed on a pooled thread but
   * applied through [setItems] on the EDT, which is also the only thread an action runs on.
   */
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

  /**
   * Every PENDING item, in the order the group shows them, with the staleness already resolved.
   *
   * Built from the last items rather than from [rows], because a collapsed group contributes no
   * rows at all: anything reading the rows would quietly copy nothing the moment someone folded
   * Pending. Folding is a view state and must not change what a copy contains.
   *
   * Reads the cached flags rather than resolving staleness, which hits the VFS and is done once per
   * rebuild on a pooled thread. Callers here are actions, and actions run on the EDT.
   */
  fun pendingNodes(): List<FeedbackItemNode> = nodesOf(Status.PENDING)

  /**
   * Whether [pendingNodes] would return anything.
   *
   * Separate from it because an action's `update` runs on the EDT on every toolbar tick and only
   * ever asks this question. Answering it by building the list would sort and allocate the whole
   * pending queue several times a second, forever, to look at `isNotEmpty()`.
   */
  fun hasPending(): Boolean = lastItems.any { it.status == Status.PENDING }

  /** One ordering for the group, so the list and a copy of it cannot disagree. */
  private fun nodesOf(status: Status): List<FeedbackItemNode> =
    lastItems.asSequence()
      .filter { it.status == status }
      .sortedByDescending { it.createdAt } // newest first inside a group
      .map {
        val f = lastFlags[it.id] ?: StaleFlags.NONE
        FeedbackItemNode(it, f.stale, f.fileMissing)
      }
      .toList()

  private fun rebuild(items: List<Feedback>, flags: Map<String, StaleFlags>) {
    lastItems = items
    lastFlags = flags

    val newRows = mutableListOf<FeedbackRow>()
    for (status in GROUP_ORDER) {
      val total = items.count { it.status == status }
      if (total == 0) continue // an empty header is noise, not information

      val isCollapsed = status in collapsed
      newRows += FeedbackRow.StatusHeader(status, total, isCollapsed)
      // Counted before the nodes are built, so a folded group of 500 resolved items costs a count
      // rather than 500 nodes nobody renders.
      if (!isCollapsed) {
        nodesOf(status).forEach { newRows += FeedbackRow.Item(it) }
      }
    }

    // Empty the model before announcing the removal, and only then install the new rows. A
    // listener that reads the model during the removal event must not be shown the new size
    // over the old indices.
    val oldSize = rows.size
    rows = emptyList()
    if (oldSize > 0) fireIntervalRemoved(this, 0, oldSize - 1)
    rows = newRows
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
