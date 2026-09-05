package dev.pinboard.ui.toolwindow

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.ui.theme.PinboardFonts
import java.awt.Container
import javax.swing.JComponent

class FeedbackCardRendererTest : BasePlatformTestCase() {

  private val scheme get() = EditorColorsManager.getInstance().globalScheme

  private fun feedback(note: String) = Feedback(
    id = "id",
    status = Status.PENDING,
    scope = Scope.SELECTION,
    note = note,
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 10,
    endLine = 12,
    codeSnapshot = "val a = 1",
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
  )

  private fun render(note: String): JComponent {
    val row = FeedbackRow.Item(FeedbackItemNode(feedback(note), stale = false, fileMissing = false))
    val list = JBList<FeedbackRow>().apply { setSize(400, 200) }
    return FeedbackCardRenderer()
      .getListCellRendererComponent(list, row, 0, false, false) as JComponent
  }

  /** Every label in the rendered card. */
  private fun labels(root: Container): List<JBLabel> = buildList {
    for (child in root.components) {
      if (child is JBLabel) add(child)
      if (child is Container) addAll(labels(child))
    }
  }

  /** The note is the only label carrying HTML - it is the one that has to wrap. */
  private fun noteLabel(note: String): JBLabel =
    labels(render(note)).single { it.text.startsWith("<html>") }

  /** Runs [body] with the editor font at [size], then puts the setting back. */
  private fun withEditorFontSize(size: Int, body: () -> Unit) {
    val original = scheme.editorFontSize
    try {
      scheme.editorFontSize = size
      body()
    } finally {
      scheme.editorFontSize = original
    }
  }

  /**
   * Turning up the IDE's font size has to make the note bigger.
   *
   * Measured rather than asserted on the label's font, because the note is rendered as HTML: Swing
   * builds a stylesheet for it, and a size baked into that markup would win over whatever font the
   * label carries. Rendered height is what the user actually sees.
   */
  fun testTheNoteGrowsWithTheIdeFontSize() {
    val note = "a note long enough to wrap onto more than one line in a docked tool window"
    var small = 0
    var large = 0

    withEditorFontSize(10) { small = noteLabel(note).preferredSize.height }
    withEditorFontSize(24) { large = noteLabel(note).preferredSize.height }

    assertTrue("note must render taller at 24pt than at 10pt (was $small vs $large)", large > small)
  }

  /** A note is prose, so it keeps the proportional UI family and takes only the editor's size. */
  fun testTheNoteIsTheUiFamilyAtTheEditorSize() {
    withEditorFontSize(19) {
      val font = noteLabel("some note").font
      assertEquals(JBFont.regular().family, font.family)
      assertEquals(19, font.size)
    }
  }

  /** The snippet is code, so it takes the editor family too - the two must stay tellable apart. */
  fun testTheCodeSnippetUsesTheEditorFont() {
    val expected = PinboardFonts.editor()
    val snippet = labels(render("some note")).single { it.text == "val a = 1" }
    assertEquals(expected.family, snippet.font.family)
    assertEquals(expected.size, snippet.font.size)
  }

  fun testALongNoteIsTruncatedRatherThanRenderedWhole() {
    val note = "x".repeat(500)
    val rendered = noteLabel(note).text
    assertTrue("must be clipped", rendered.length < note.length)
    assertTrue("must say it was clipped", rendered.contains("…"))
  }

  fun testMarkupInANoteIsShownNotRendered() {
    assertTrue(noteLabel("<b>not bold</b>").text.contains("&lt;b&gt;"))
  }
}
