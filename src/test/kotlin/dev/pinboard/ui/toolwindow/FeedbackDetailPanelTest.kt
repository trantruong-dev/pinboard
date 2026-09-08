package dev.pinboard.ui.toolwindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import javax.swing.JComponent
import javax.swing.JEditorPane

class FeedbackDetailPanelTest : BasePlatformTestCase() {

  private fun node(
    thread: List<Message> = emptyList(),
    codeSnapshot: String? = "val x = 1",
  ) = FeedbackItemNode(
    feedback = Feedback(
      id = "id",
      status = Status.PENDING,
      scope = Scope.SELECTION,
      note = "note",
      filePath = "src/Foo.kt",
      language = "Kotlin",
      startLine = 1,
      endLine = 2,
      codeSnapshot = codeSnapshot,
      contentSha256 = "sha",
      symbolPath = null,
      vcsRevision = null,
      thread = thread,
      createdAt = 1_757_260_800_000,
      updatedAt = 1_757_260_800_000,
    ),
    stale = false,
    fileMissing = false,
  )

  private fun message() =
    Message(id = "m1", author = Author.AGENT, body = "on it", createdAt = 1_757_260_860_000)

  private fun newPanel() = FeedbackDetailPanel(project, testRootDisposable) {
    DefaultActionGroup(CopyFeedbackAction { null })
  }

  /** Without focus there is no caret, and without a caret the text cannot be selected or copied. */
  fun testNoteTextIsFocusable() {
    val panel = newPanel()
    panel.show(node(thread = listOf(message())))

    val panes = UIUtil.findComponentsOfType(panel, JEditorPane::class.java)
    assertFalse("expected the header, note and message panes", panes.isEmpty())
    panes.forEach { assertTrue("pane is not focusable", it.isFocusable) }
  }

  /** Read-only has to mean viewer: a disabled editor has no caret, no copy, and paints dimmed. */
  fun testCodePreviewIsLiveViewer() {
    val panel = newPanel()
    panel.show(node())

    val fields = UIUtil.findComponentsOfType(panel, EditorTextField::class.java)
    assertEquals(1, fields.size)
    assertTrue("preview editor is disabled", fields[0].isEnabled)
    assertTrue("preview editor is not a viewer", fields[0].isViewer)
  }

  /**
   * Swing delivers a mouse event to the deepest component that has a listener and never bubbles it
   * to an ancestor, so a handler on the panel root alone is dead the moment content covers it.
   * Each text surface has to carry its own or the right-click menu simply never appears.
   */
  fun testEveryTextSurfaceCarriesTheContextMenu() {
    val panel = newPanel()
    panel.show(node(thread = listOf(message())))

    val panes = UIUtil.findComponentsOfType(panel, JEditorPane::class.java)
    assertFalse(panes.isEmpty())
    panes.forEach { pane ->
      assertTrue("no popup menu on a text pane", pane.mouseListeners.any { it is PopupHandler })
    }
    val scrollPane = panel.getComponent(0) as JComponent
    assertTrue(
      "no popup menu on the scroll pane",
      scrollPane.mouseListeners.any { it is PopupHandler },
    )
  }

  /**
   * The snapshot is the only record of what was pinned once the file has moved on, so it has to be
   * readable as a block. The one-line default showed the first line and nothing else, and a long
   * snapshot left uncapped would push the conversation off the panel instead.
   */
  fun testCodePreviewIsAReadableBlockAndCapped() {
    val panel = newPanel()
    panel.show(node(codeSnapshot = (1..80).joinToString(separator = "\n") { "line $it" }))

    val field = UIUtil.findComponentsOfType(panel, EditorTextField::class.java).single()
    val preview = field.parent as JComponent
    val oneLine = field.getFontMetrics(field.font).height

    assertTrue("preview is only one line tall", preview.preferredSize.height > oneLine * 3)
    assertTrue("preview grew without bound", preview.preferredSize.height <= JBUI.scale(220))
  }

  /**
   * The point of the early-return: a rebuild triggered by some other item must leave the component
   * tree - and the text selection inside it - exactly where it was.
   */
  fun testShowWithEqualNodeKeepsSameComponent() {
    val panel = newPanel()
    panel.show(node())
    val first = panel.getComponent(0)

    panel.show(node())

    assertSame(first, panel.getComponent(0))
  }

  /** And the limit of it: a new message on the shown item is a change the user must see. */
  fun testShowWithChangedNodeRebuilds() {
    val panel = newPanel()
    panel.show(node())
    val first = panel.getComponent(0)

    panel.show(node(thread = listOf(message())))

    assertNotSame(first, panel.getComponent(0))
  }
}
