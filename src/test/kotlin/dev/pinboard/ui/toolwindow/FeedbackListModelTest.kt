package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackListModelTest {

  private fun item(id: String, status: Status, createdAt: Long) = Feedback(
    id = id,
    status = status,
    scope = Scope.SELECTION,
    note = "note-$id",
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 1,
    endLine = 2,
    codeSnapshot = "code",
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
    createdAt = createdAt,
    updatedAt = createdAt,
  )

  /** A model with every group expanded, so tests can see the items and not just the headers. */
  private fun modelOf(
    items: List<Feedback>,
    flags: Map<String, StaleFlags> = emptyMap(),
  ): FeedbackListModel = FeedbackListModel().apply {
    setItems(items, flags)
    FeedbackListModel.COLLAPSED_BY_DEFAULT.forEach { toggleCollapsed(it) }
  }

  private fun rows(model: FeedbackListModel): List<FeedbackRow> =
    (0 until model.size).map { model.getElementAt(it) }

  private fun itemsUnder(model: FeedbackListModel, status: Status): List<FeedbackItemNode> {
    val all = rows(model)
    val start = all.indexOfFirst { it is FeedbackRow.StatusHeader && it.status == status }
    if (start < 0) return emptyList()
    return all.drop(start + 1)
      .takeWhile { it is FeedbackRow.Item }
      .map { (it as FeedbackRow.Item).node }
  }

  @Test
  fun groupsAreOrderedPendingFirstAndEmptyOnesHidden() {
    val model = modelOf(listOf(item("r", Status.RESOLVED, 1), item("p", Status.PENDING, 2)))
    // ACKNOWLEDGED and DISMISSED have no items, so they must not render as empty headers.
    assertEquals(listOf(Status.PENDING, Status.RESOLVED), model.headers().map { it.status })
  }

  @Test
  fun newestItemsComeFirstWithinAGroup() {
    val model = modelOf(listOf(item("old", Status.PENDING, 100), item("new", Status.PENDING, 200)))
    assertEquals(listOf("new", "old"), itemsUnder(model, Status.PENDING).map { it.feedback.id })
  }

  @Test
  fun headerTotalMatchesItemCount() {
    val model = modelOf(
      listOf(
        item("a", Status.PENDING, 1),
        item("b", Status.PENDING, 2),
        item("c", Status.DISMISSED, 3),
      ),
    )
    assertEquals(listOf(2, 1), model.headers().map { it.total })
  }

  @Test
  fun staleFlagsAreCarriedOntoNodes() {
    val model = modelOf(
      listOf(item("a", Status.PENDING, 1), item("b", Status.PENDING, 2)),
      mapOf("a" to StaleFlags(stale = true, fileMissing = true)),
    )
    val byId = itemsUnder(model, Status.PENDING).associateBy { it.feedback.id }
    assertTrue(byId.getValue("a").stale)
    assertTrue(byId.getValue("a").fileMissing)
    // Items missing from the map default to "not stale" rather than throwing.
    assertFalse(byId.getValue("b").stale)
    assertFalse(byId.getValue("b").fileMissing)
  }

  @Test
  fun resolvedAndDismissedStartCollapsed() {
    assertEquals(setOf(Status.RESOLVED, Status.DISMISSED), FeedbackListModel.COLLAPSED_BY_DEFAULT)

    val model = FeedbackListModel()
    model.setItems(listOf(item("r", Status.RESOLVED, 1), item("p", Status.PENDING, 2)), emptyMap())

    // Both headers render, but only the open group contributes item rows.
    assertEquals(listOf(Status.PENDING, Status.RESOLVED), model.headers().map { it.status })
    assertEquals(listOf("p"), itemsUnder(model, Status.PENDING).map { it.feedback.id })
    assertEquals(emptyList<String>(), itemsUnder(model, Status.RESOLVED).map { it.feedback.id })
  }

  @Test
  fun collapsedHeaderStillReportsTheHiddenTotal() {
    // The count is the only thing left to read once a group is folded, so it must count items, not
    // rendered rows.
    val model = FeedbackListModel()
    model.setItems(List(3) { item("r$it", Status.RESOLVED, it.toLong()) }, emptyMap())

    val header = model.headers().single()
    assertTrue(header.collapsed)
    assertEquals(3, header.total)
    assertEquals(1, model.size)
  }

  @Test
  fun togglingAGroupShowsAndHidesItsItems() {
    val model = FeedbackListModel()
    model.setItems(listOf(item("p", Status.PENDING, 1)), emptyMap())
    assertEquals(1, itemsUnder(model, Status.PENDING).size)

    model.toggleCollapsed(Status.PENDING)
    assertTrue(model.isCollapsed(Status.PENDING))
    assertEquals(emptyList<FeedbackItemNode>(), itemsUnder(model, Status.PENDING))

    model.toggleCollapsed(Status.PENDING)
    assertFalse(model.isCollapsed(Status.PENDING))
    assertEquals(1, itemsUnder(model, Status.PENDING).size)
  }

  @Test
  fun foldStateSurvivesAStoreUpdate() {
    // Rebuilds are driven by agent activity, which happens while the user is reading. A group they
    // folded must not spring back open underneath them.
    val model = FeedbackListModel()
    model.setItems(listOf(item("p", Status.PENDING, 1)), emptyMap())
    model.toggleCollapsed(Status.PENDING)

    model.setItems(listOf(item("p", Status.PENDING, 1), item("q", Status.PENDING, 2)), emptyMap())

    assertTrue(model.headers().single().collapsed)
    assertEquals(2, model.headers().single().total)
  }

  @Test
  fun pendingNodesCarriesOnlyPendingInGroupOrder() {
    val model = modelOf(
      listOf(
        item("old", Status.PENDING, 100),
        item("new", Status.PENDING, 200),
        item("a", Status.ACKNOWLEDGED, 300),
        item("r", Status.RESOLVED, 400),
        item("d", Status.DISMISSED, 500),
      ),
    )
    // Same order the Pending group renders in, so the copy reads the way the queue looks.
    assertEquals(listOf("new", "old"), model.pendingNodes().map { it.feedback.id })
  }

  /** Folding is a view state. A folded group has no rows at all, so anything built from the rows
   *  would copy nothing here - which is the whole reason this is built from the items instead. */
  @Test
  fun pendingNodesIgnoresFolding() {
    val model = modelOf(listOf(item("p", Status.PENDING, 1), item("q", Status.PENDING, 2)))
    val open = model.pendingNodes()

    model.toggleCollapsed(Status.PENDING)

    assertTrue(model.isCollapsed(Status.PENDING))
    assertEquals(emptyList<FeedbackItemNode>(), itemsUnder(model, Status.PENDING))
    assertEquals(open, model.pendingNodes())
    assertEquals(2, model.pendingNodes().size)
  }

  @Test
  fun pendingNodesCarriesTheResolvedStaleFlags() {
    val model = modelOf(
      listOf(item("a", Status.PENDING, 1)),
      mapOf("a" to StaleFlags(stale = true, fileMissing = false)),
    )
    assertTrue(model.pendingNodes().single().stale)
  }

  /** The action asks the cheap question on every tick and the expensive one only when invoked, so
   *  the two must never disagree about whether there is anything to copy. */
  @Test
  fun hasPendingAgreesWithPendingNodes() {
    val cases = listOf(
      emptyList(),
      listOf(item("r", Status.RESOLVED, 1)),
      listOf(item("p", Status.PENDING, 1)),
      listOf(item("p", Status.PENDING, 1), item("a", Status.ACKNOWLEDGED, 2)),
    )
    for (items in cases) {
      val model = modelOf(items)
      assertEquals(items.toString(), model.pendingNodes().isNotEmpty(), model.hasPending())
    }
  }

  @Test
  fun pendingNodesIsEmptyWithNothingPending() {
    assertEquals(emptyList<FeedbackItemNode>(), modelOf(emptyList()).pendingNodes())
    assertEquals(
      emptyList<FeedbackItemNode>(),
      modelOf(listOf(item("r", Status.RESOLVED, 1))).pendingNodes(),
    )
  }

  @Test
  fun emptyQueueProducesNoRows() {
    val model = modelOf(emptyList())
    assertEquals(0, model.size)
    assertEquals(emptyList<FeedbackRow.StatusHeader>(), model.headers())
  }
}
