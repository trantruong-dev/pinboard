package dev.pinboard.ui.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBFont

class PinboardFontsTest : BasePlatformTestCase() {

  private val scheme get() = EditorColorsManager.getInstance().globalScheme

  /**
   * Both halves of the font come from the IDE.
   *
   * Taking only the family and choosing a size here ignores the user's editor font size setting
   * entirely, which is what this guards.
   */
  fun testTheCodeFontIsTheEditorsOwnFamilyAndSize() {
    val expected = scheme.getFont(EditorFontType.PLAIN)
    val actual = PinboardFonts.editor()

    assertEquals(expected.family, actual.family)
    assertEquals(expected.size, actual.size)
  }

  /** Scheme sizes are already scaled, so scaling again would double-count HiDPI. */
  fun testTheCodeFontSizeIsNotScaledASecondTime() {
    assertEquals(scheme.editorFontSize, PinboardFonts.editor().size)
  }

  /**
   * Read on every call, never cached. A cached font would freeze at whatever the settings were the
   * first time something was painted, and stay wrong until the IDE restarted.
   */
  fun testChangingTheEditorFontSizeChangesWhatIsPainted() {
    val original = scheme.editorFontSize
    try {
      scheme.editorFontSize = original + 4
      assertEquals(original + 4, PinboardFonts.editor().size)
    } finally {
      scheme.editorFontSize = original
    }
    assertEquals(original, PinboardFonts.editor().size)
  }

  /**
   * A note reads as prose, so it keeps the proportional UI family. Falling back to the editor's
   * family would set a paragraph in a monospaced face inside a narrow docked panel.
   */
  fun testTheNoteFontKeepsTheUiFamily() {
    assertEquals(JBFont.regular().family, PinboardFonts.note().family)
  }

  /** Its size comes from the editor, which is the setting users reach for to make text readable. */
  fun testTheNoteFontTakesTheEditorFontSize() {
    val original = scheme.editorFontSize
    try {
      scheme.editorFontSize = original + 5
      assertEquals(original + 5, PinboardFonts.note().size)
    } finally {
      scheme.editorFontSize = original
    }
    assertEquals(original, PinboardFonts.note().size)
  }
}
