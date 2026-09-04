package dev.pinboard.ui.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class FeedbackListPanelTest : BasePlatformTestCase() {

  private fun item(id: String, status: Status = Status.PENDING, createdAt: Long = System.currentTimeMillis()) =
    Feedback(
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

  private fun newPanel(): FeedbackListPanel {
    val content = ContentFactory.getInstance().createContent(null, "Feedback", false)
    val panel = FeedbackListPanel(project, content, testRootDisposable)
    content.component = panel
    return panel
  }

  /** Drives the EDT until [condition] holds, so async rebuilds can settle without a fixed sleep. */
  private fun waitFor(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 20_000
    while (System.currentTimeMillis() < deadline) {
      UIUtil.dispatchAllInvocationEvents()
      if (condition()) return
      Thread.sleep(10)
    }
    fail("Timed out waiting for: $what")
  }

  private fun groups(panel: FeedbackListPanel): List<StatusGroupNode> {
    val root = panel.treeForTest().model.root as DefaultMutableTreeNode
    return (0 until root.childCount).map {
      (root.getChildAt(it) as DefaultMutableTreeNode).userObject as StatusGroupNode
    }
  }

  private fun nodeFor(panel: FeedbackListPanel, id: String): DefaultMutableTreeNode? {
    val root = panel.treeForTest().model.root as DefaultMutableTreeNode
    for (g in 0 until root.childCount) {
      val group = root.getChildAt(g) as DefaultMutableTreeNode
      for (i in 0 until group.childCount) {
        val node = group.getChildAt(i) as DefaultMutableTreeNode
        val payload = node.userObject as? FeedbackItemNode ?: continue
        if (payload.feedback.id == id) return node
      }
    }
    return null
  }

  fun testGroupsAndPendingBadgeReflectStore() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(3) { store.add(item("p$it")) }
    repeat(2) { store.add(item("r$it", Status.RESOLVED)) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 3 }

    assertEquals(listOf(Status.PENDING, Status.RESOLVED), groups(panel).map { it.status })
    assertEquals(3, groups(panel)[0].count)
    assertEquals(2, groups(panel)[1].count)
  }

  /**
   * The core promise of the panel: an agent changing status through an MCP tool shows up here with
   * no manual refresh. Nothing in this test calls a rebuild - it only mutates the store, exactly
   * like `feedback_resolve` does.
   */
  fun testAgentResolveUpdatesPanelWithoutManualRefresh() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(20) { store.add(item("id$it")) }

    val panel = newPanel()
    waitFor("20 pending rendered") { panel.pendingCountForTest() == 20 }

    repeat(5) { store.updateStatus("id$it", Status.RESOLVED) }
    waitFor("panel reflects agent resolves") { panel.pendingCountForTest() == 15 }

    assertEquals(15, groups(panel).first { it.status == Status.PENDING }.count)
    assertEquals(5, groups(panel).first { it.status == Status.RESOLVED }.count)
  }

  fun testSelectionSurvivesRebuild() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(5) { store.add(item("keep$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 5 }

    val node = nodeFor(panel, "keep2")!!
    panel.treeForTest().selectionPath = TreePath(node.path)

    // An unrelated item changes status; the user's cursor must not move.
    store.updateStatus("keep4", Status.ACKNOWLEDGED)
    waitFor("panel reflects the status change") { panel.pendingCountForTest() == 4 }

    val selected = panel.treeForTest().lastSelectedPathComponent as? DefaultMutableTreeNode
    assertEquals("keep2", (selected?.userObject as? FeedbackItemNode)?.feedback?.id)
  }

  fun testDeletedItemClearsSelectionInsteadOfThrowing() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("gone"))

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 1 }
    panel.treeForTest().selectionPath = TreePath(nodeFor(panel, "gone")!!.path)

    store.delete("gone")
    waitFor("panel empties") { panel.pendingCountForTest() == 0 }

    assertNull(panel.treeForTest().lastSelectedPathComponent)
    assertEquals(emptyList<StatusGroupNode>(), groups(panel))
  }

  /** A pinned file can be deleted after capture; navigating must report, not throw. */
  fun testNavigateToMissingFileDoesNotThrow() {
    FeedbackNavigator.navigate(project, item("missing").copy(filePath = "does/not/exist/Nope.kt"))
  }

  /** Project-scope items have no file to jump to; navigation is a no-op. */
  fun testNavigateProjectScopeIsNoop() {
    FeedbackNavigator.navigate(project, item("proj").copy(scope = Scope.PROJECT, filePath = null))
  }
}
