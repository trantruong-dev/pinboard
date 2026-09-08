package dev.pinboard.ui.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore

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

  private fun headers(panel: FeedbackListPanel): List<FeedbackRow.StatusHeader> {
    val model = panel.listForTest().model
    return (0 until model.size)
      .map { model.getElementAt(it) }
      .filterIsInstance<FeedbackRow.StatusHeader>()
  }

  private fun indexOf(panel: FeedbackListPanel, id: String): Int {
    val model = panel.listForTest().model
    for (i in 0 until model.size) {
      val row = model.getElementAt(i)
      if (row is FeedbackRow.Item && row.node.feedback.id == id) return i
    }
    return -1
  }

  private fun selectedId(panel: FeedbackListPanel): String? =
    (panel.listForTest().selectedValue as? FeedbackRow.Item)?.node?.feedback?.id

  fun testGroupsAndPendingBadgeReflectStore() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(3) { store.add(item("p$it")) }
    repeat(2) { store.add(item("r$it", Status.RESOLVED)) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 3 }

    assertEquals(listOf(Status.PENDING, Status.RESOLVED), headers(panel).map { it.status })
    assertEquals(3, headers(panel)[0].total)
    assertEquals(2, headers(panel)[1].total)
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

    assertEquals(15, headers(panel).first { it.status == Status.PENDING }.total)
    assertEquals(5, headers(panel).first { it.status == Status.RESOLVED }.total)
  }

  fun testSelectionSurvivesRebuild() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(5) { store.add(item("keep$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 5 }

    panel.listForTest().selectedIndex = indexOf(panel, "keep2")

    // An unrelated item changes status; the user's cursor must not move.
    store.updateStatus("keep4", Status.ACKNOWLEDGED)
    waitFor("panel reflects the status change") { panel.pendingCountForTest() == 4 }

    assertEquals("keep2", selectedId(panel))
  }

  /**
   * A rebuild caused by an item the user is not looking at must not reach into the detail panel.
   * Rebuilding it throws away whatever text the user was in the middle of selecting there.
   */
  fun testUnrelatedUpdateLeavesDetailPanelUntouched() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(5) { store.add(item("keep$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 5 }

    panel.listForTest().selectedIndex = indexOf(panel, "keep2")
    val shown = panel.detailForTest().getComponent(0)

    store.updateStatus("keep4", Status.ACKNOWLEDGED)
    waitFor("panel reflects the status change") { panel.pendingCountForTest() == 4 }

    assertSame(shown, panel.detailForTest().getComponent(0))
  }

  /**
   * The limit of the guard above, through the real rebuild path rather than a direct `show` call.
   * A guard widened by accident would keep the negative test green while the panel silently stopped
   * updating for the item the user is actually watching.
   */
  fun testUpdateToTheShownItemDoesRebuildTheDetailPanel() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(5) { store.add(item("keep$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 5 }

    panel.listForTest().selectedIndex = indexOf(panel, "keep2")
    val shown = panel.detailForTest().getComponent(0)

    store.updateStatus("keep2", Status.ACKNOWLEDGED)
    waitFor("detail panel rebuilds for the shown item") {
      panel.detailForTest().getComponent(0) !== shown
    }

    assertEquals("keep2", selectedId(panel))
  }

  fun testDeletedItemClearsSelectionInsteadOfThrowing() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("gone"))

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 1 }
    panel.listForTest().selectedIndex = indexOf(panel, "gone")

    store.delete("gone")
    waitFor("panel empties") { panel.pendingCountForTest() == 0 }

    assertNull(selectedId(panel))
    assertEquals(emptyList<FeedbackRow.StatusHeader>(), headers(panel))
  }

  /**
   * Folding is the whole reason the list keeps state of its own, and a rebuild happens whenever an
   * agent touches the queue. A group the user folded must stay folded through one.
   */
  fun testFoldedGroupStaysFoldedAcrossAnAgentUpdate() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(3) { store.add(item("fold$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 3 }
    assertTrue(indexOf(panel, "fold0") > 0)

    panel.toggleGroupForTest(Status.PENDING)
    assertEquals(-1, indexOf(panel, "fold0"))

    store.updateStatus("fold2", Status.ACKNOWLEDGED)
    waitFor("panel reflects the status change") { panel.pendingCountForTest() == 2 }

    assertTrue(headers(panel).first { it.status == Status.PENDING }.collapsed)
    assertEquals(-1, indexOf(panel, "fold0"))
  }

  /**
   * Folding rebuilds the rows the same way a store mutation does, so it needs the same guard.
   * Expanding Resolved to glance at something must not deselect the item being read, nor tear down
   * the detail panel showing it.
   */
  fun testFoldingAnotherGroupKeepsSelectionAndDetail() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(3) { store.add(item("keep$it")) }
    store.add(item("done", Status.RESOLVED))

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 3 }

    panel.listForTest().selectedIndex = indexOf(panel, "keep1")
    val shown = panel.detailForTest().getComponent(0)

    panel.toggleGroupForTest(Status.RESOLVED) // starts collapsed, so this expands it

    assertTrue(indexOf(panel, "done") > 0)
    assertEquals("keep1", selectedId(panel))
    assertSame(shown, panel.detailForTest().getComponent(0))
  }

  /** Folding the group that holds the selection does clear the panel: the item is off screen. */
  fun testFoldingTheSelectedGroupClearsTheDetail() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    repeat(3) { store.add(item("hide$it")) }

    val panel = newPanel()
    waitFor("initial render") { panel.pendingCountForTest() == 3 }

    panel.listForTest().selectedIndex = indexOf(panel, "hide1")
    val shown = panel.detailForTest().getComponent(0)

    panel.toggleGroupForTest(Status.PENDING)

    assertEquals(-1, indexOf(panel, "hide1"))
    assertNull(selectedId(panel))
    assertNotSame(shown, panel.detailForTest().getComponent(0))
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
