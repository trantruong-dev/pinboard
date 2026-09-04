package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.tree.DefaultMutableTreeNode

class FeedbackTreeModelTest {

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

  private fun groups(model: javax.swing.tree.DefaultTreeModel): List<StatusGroupNode> {
    val root = model.root as DefaultMutableTreeNode
    return (0 until root.childCount).map {
      (root.getChildAt(it) as DefaultMutableTreeNode).userObject as StatusGroupNode
    }
  }

  private fun itemsOf(model: javax.swing.tree.DefaultTreeModel, groupIndex: Int): List<FeedbackItemNode> {
    val root = model.root as DefaultMutableTreeNode
    val group = root.getChildAt(groupIndex) as DefaultMutableTreeNode
    return (0 until group.childCount).map {
      (group.getChildAt(it) as DefaultMutableTreeNode).userObject as FeedbackItemNode
    }
  }

  @Test
  fun groupsAreOrderedPendingFirstAndEmptyOnesHidden() {
    val model = FeedbackTreeModel.build(
      listOf(
        item("r", Status.RESOLVED, 1),
        item("p", Status.PENDING, 2),
      ),
      emptyMap(),
    )
    // ACKNOWLEDGED and DISMISSED have no items, so they must not render as empty headers.
    assertEquals(listOf(Status.PENDING, Status.RESOLVED), groups(model).map { it.status })
  }

  @Test
  fun newestItemsComeFirstWithinAGroup() {
    val model = FeedbackTreeModel.build(
      listOf(
        item("old", Status.PENDING, 100),
        item("new", Status.PENDING, 200),
      ),
      emptyMap(),
    )
    assertEquals(listOf("new", "old"), itemsOf(model, 0).map { it.feedback.id })
  }

  @Test
  fun groupCountMatchesItemCount() {
    val model = FeedbackTreeModel.build(
      listOf(
        item("a", Status.PENDING, 1),
        item("b", Status.PENDING, 2),
        item("c", Status.DISMISSED, 3),
      ),
      emptyMap(),
    )
    assertEquals(2, groups(model)[0].count)
    assertEquals(1, groups(model)[1].count)
  }

  @Test
  fun staleFlagsAreCarriedOntoNodes() {
    val model = FeedbackTreeModel.build(
      listOf(item("a", Status.PENDING, 1), item("b", Status.PENDING, 2)),
      mapOf("a" to StaleFlags(stale = true, fileMissing = true)),
    )
    val byId = itemsOf(model, 0).associateBy { it.feedback.id }
    assertTrue(byId.getValue("a").stale)
    assertTrue(byId.getValue("a").fileMissing)
    // Items missing from the map default to "not stale" rather than throwing.
    assertFalse(byId.getValue("b").stale)
    assertFalse(byId.getValue("b").fileMissing)
  }

  @Test
  fun resolvedAndDismissedStartCollapsed() {
    assertEquals(setOf(Status.RESOLVED, Status.DISMISSED), FeedbackTreeModel.COLLAPSED_BY_DEFAULT)
  }

  @Test
  fun emptyQueueProducesNoGroups() {
    assertEquals(emptyList<StatusGroupNode>(), groups(FeedbackTreeModel.build(emptyList(), emptyMap())))
  }

  @Test
  fun testRowFileNameAcceptsEitherSeparator() {
    // Items captured before paths were normalised still carry Windows separators. Splitting on
    // '/' alone returned the whole path for those, so every such row was far too wide to read in
    // a docked tool window.
    assertEquals("Foo.kt", FeedbackCellRenderer.fileName("src/main/kotlin/Foo.kt"))
    assertEquals("Foo.kt", FeedbackCellRenderer.fileName("src" + '\\' + "main" + '\\' + "Foo.kt"))
    assertEquals("Foo.kt", FeedbackCellRenderer.fileName("Foo.kt"))
  }
}
