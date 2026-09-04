package dev.pinboard.ui.dialog

import dev.pinboard.capture.SelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class InlineFeedbackPopupTest {

  private fun snapshot(
    filePath: String? = "src/main/kotlin/Foo.kt",
    startLine: Int? = 40,
    endLine: Int? = 52,
  ) = SelectionSnapshot(
    filePath = filePath,
    language = "Kotlin",
    startLine = startLine,
    endLine = endLine,
    codeSnapshot = "fun foo() {}",
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
  )

  /** The balloon sits on the code it is about, so the path would only crowd a narrow header. */
  @Test
  fun theHeaderShowsOnlyTheFileNameAndRange() {
    assertEquals("Foo.kt : 40-52", InlineFeedbackPopup.shortLocation(snapshot()))
  }

  @Test
  fun aSingleLineIsNotWrittenAsARange() {
    assertEquals("Foo.kt : 40", InlineFeedbackPopup.shortLocation(snapshot(startLine = 40, endLine = 40)))
    assertEquals("Foo.kt : 40", InlineFeedbackPopup.shortLocation(snapshot(startLine = 40, endLine = null)))
  }

  @Test
  fun aWholeFileHasNoRange() {
    assertEquals("Foo.kt", InlineFeedbackPopup.shortLocation(snapshot(startLine = null, endLine = null)))
  }

  @Test
  fun noFileMeansProjectScope() {
    assertEquals("whole project", InlineFeedbackPopup.shortLocation(snapshot(filePath = null)))
    assertEquals("whole project", InlineFeedbackPopup.shortLocation(null))
  }

  /**
   * Plain Enter has to keep inserting a newline - the note is multi-line and people write more than
   * one sentence in it - so submitting must be a chord, and both platforms' chords must work.
   */
  @Test
  fun submitIsAChordNotPlainEnter() {
    val shortcuts = InlineFeedbackPopup.submitShortcuts()
    assertTrue(shortcuts.contains(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)))
    assertTrue(shortcuts.contains(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)))
    assertTrue(shortcuts.none { it.modifiers == 0 })
  }
}
