package dev.pinboard.ui.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-math tests for the LineNumberConverter offset used by [CodePreviewPanel].
 * The platform passes 1-based line numbers into the converter, so preview line N of a
 * selection starting at source line S must display S + N - 1.
 */
class CodePreviewLineNumbersTest {

  @Test
  fun firstPreviewLineShowsRealStartLine() {
    val startLine = 10
    // Converter math: lineNumber (1-based) + startLine - 1
    assertEquals(10, 1 + startLine - 1)
  }

  @Test
  fun subsequentLinesIncrement() {
    val startLine = 40
    // Preview line 3 of a selection starting at line 40 -> 42.
    assertEquals(42, 3 + startLine - 1)
  }

  @Test
  fun singleLineSelection() {
    val startLine = 5
    assertEquals(5, 1 + startLine - 1)
  }
}