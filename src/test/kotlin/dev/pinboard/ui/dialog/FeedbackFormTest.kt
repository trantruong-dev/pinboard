package dev.pinboard.ui.dialog

import dev.pinboard.capture.SelectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackFormTest {

  private fun snapshot(
    filePath: String? = "src/main/kotlin/Foo.kt",
    startLine: Int? = 40,
    endLine: Int? = 52,
    code: String? = "fun foo() {}",
  ) = SelectionSnapshot(
    filePath = filePath,
    language = "Kotlin",
    startLine = startLine,
    endLine = endLine,
    codeSnapshot = code,
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
  )

  @Test
  fun aRangeShowsBothEnds() {
    assertEquals("src/main/kotlin/Foo.kt : 40-52", FeedbackForm.location(snapshot()))
  }

  @Test
  fun aSingleLineIsNotWrittenAsARange() {
    // "40-40" reads as if two lines were pinned when only one was.
    assertEquals("src/main/kotlin/Foo.kt : 40", FeedbackForm.location(snapshot(startLine = 40, endLine = 40)))
    assertEquals("src/main/kotlin/Foo.kt : 40", FeedbackForm.location(snapshot(startLine = 40, endLine = null)))
  }

  @Test
  fun wholeFileScopeShowsJustThePath() {
    assertEquals(
      "src/main/kotlin/Foo.kt",
      FeedbackForm.location(snapshot(startLine = null, endLine = null)),
    )
  }

  @Test
  fun projectScopeSaysSo() {
    assertEquals("Whole project", FeedbackForm.location(snapshot(filePath = null)))
    assertEquals("Whole project", FeedbackForm.location(null))
  }

  @Test
  fun theStripKeepsTheWholePathNotJustTheFileName() {
    // Two files can share a name, and the strip is the last moment to notice the wrong one was
    // picked, so the balloon's shortened form must not be what the dialog shows.
    val location = FeedbackForm.location(snapshot())
    assertTrue(location.startsWith("src/main/kotlin/"))
  }

  @Test
  fun aShortSnippetIsShownWhole() {
    val code = (1..FeedbackForm.SNIPPET_PREVIEW_LINES).joinToString("\n") { "line $it" }
    assertEquals(code, FeedbackForm.trimToFit(code))
  }

  @Test
  fun aLongSnippetIsCappedAndSaysHowMuchIsHidden() {
    val code = (1..FeedbackForm.SNIPPET_PREVIEW_LINES + 5).joinToString("\n") { "line $it" }
    val trimmed = FeedbackForm.trimToFit(code)

    val lines = trimmed.lines()
    assertEquals(FeedbackForm.SNIPPET_PREVIEW_LINES + 1, lines.size)
    assertEquals("line 1", lines.first())
    assertEquals("… 5 more lines", lines.last())
  }

  @Test
  fun oneHiddenLineIsNotPluralised() {
    val code = (1..FeedbackForm.SNIPPET_PREVIEW_LINES + 1).joinToString("\n") { "line $it" }
    assertEquals("… 1 more line", FeedbackForm.trimToFit(code).lines().last())
  }

  /** Trimming is a preview cap only. The stored snapshot is never what the preview shows. */
  @Test
  fun trimmingNeverTouchesTheCapturedCode() {
    val code = (1..100).joinToString("\n") { "line $it" }
    val captured = snapshot(code = code)
    FeedbackForm.trimToFit(captured.codeSnapshot!!)
    assertEquals(code, captured.codeSnapshot)
  }
}
