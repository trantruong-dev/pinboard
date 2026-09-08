package dev.pinboard.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Copy is one menu entry doing one of two things, so what decides between them is the whole
 * behaviour worth testing.
 */
class CopyFeedbackActionTest : BasePlatformTestCase() {

  private fun node() = FeedbackItemNode(
    feedback = Feedback(
      id = "id",
      status = Status.PENDING,
      scope = Scope.SELECTION,
      note = "the whole note",
      filePath = "src/Foo.kt",
      language = "Kotlin",
      startLine = 12,
      endLine = 20,
      codeSnapshot = null,
      contentSha256 = "sha",
      symbolPath = null,
      vcsRevision = null,
      createdAt = 1_757_260_800_000,
      updatedAt = 1_757_260_800_000,
    ),
    stale = false,
    fileMissing = false,
  )

  private fun paneWithSelection(text: String, from: Int, to: Int): JEditorPane {
    val pane = JEditorPane("text/plain", text)
    pane.select(from, to)
    return pane
  }

  private fun event(component: Any?): AnActionEvent {
    val context = SimpleDataContext.builder()
      .add(PlatformDataKeys.CONTEXT_COMPONENT, component as? java.awt.Component)
      .build()
    return AnActionEvent.createFromDataContext(ActionPlaces.TOOLWINDOW_POPUP, null, context)
  }

  private fun action() = CopyFeedbackAction { node() }

  fun testHighlightedTextIsWhatGetsCopied() {
    val e = event(paneWithSelection("the whole note", 4, 9))
    val action = action()

    action.update(e)
    action.actionPerformed(e)

    assertEquals("Copy Selection", e.presentation.text)
    assertEquals("whole", clipboardText())
  }

  /** No selection means the gesture can only sensibly mean the item. */
  fun testWithoutASelectionTheWholeItemIsCopied() {
    val e = event(paneWithSelection("the whole note", 0, 0))
    val action = action()

    action.update(e)
    action.actionPerformed(e)

    assertEquals("Copy", e.presentation.text)
    assertTrue(clipboardText().startsWith("### src/Foo.kt:12-20 - Pending"))
  }

  /** The toolbar and the row menu target the list, which holds no text selection. */
  fun testANonTextComponentCopiesTheWholeItem() {
    val e = event(JPanel())
    val action = action()

    action.update(e)
    action.actionPerformed(e)

    assertEquals("Copy", e.presentation.text)
    assertTrue(clipboardText().startsWith("### src/Foo.kt:12-20 - Pending"))
  }

  fun testDisabledWithNeitherASelectionNorAnItem() {
    val e = event(JPanel())
    CopyFeedbackAction { null }.update(e)

    assertFalse(e.presentation.isEnabled)
  }

  /** A selection is copyable even with no row selected, which is why enablement checks both. */
  fun testEnabledForASelectionWithNoRowSelected() {
    val e = event(paneWithSelection("the whole note", 4, 9))
    CopyFeedbackAction { null }.update(e)

    assertTrue(e.presentation.isEnabled)
  }

  fun testSelectedTextInIgnoresEverythingButATextComponent() {
    assertNull(selectedTextIn(null))
    assertNull(selectedTextIn(JPanel()))
    assertNull(selectedTextIn(paneWithSelection("abc", 0, 0)))
    assertEquals("ab", selectedTextIn(paneWithSelection("abc", 0, 2)))
  }

  private fun clipboardText(): String =
    checkNotNull(
      com.intellij.openapi.ide.CopyPasteManager.getInstance()
        .getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor),
    ) { "nothing on the clipboard" }
}
