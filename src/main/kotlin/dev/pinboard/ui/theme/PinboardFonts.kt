package dev.pinboard.ui.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBFont
import java.awt.Font

/**
 * The fonts Pinboard paints with.
 *
 * Everything here comes from the IDE's own settings rather than from a number chosen here, so
 * changing the editor font - family or size - in Settings changes what Pinboard renders too. Chrome
 * text uses `JBFont`, which already tracks the UI font the same way.
 *
 * Read on every paint, never cached: a cached `Font` would freeze at whatever the settings were the
 * first time a card was drawn.
 */
object PinboardFonts {

  /**
   * The editor's own font, family and size together.
   *
   * Asking the scheme for the whole font matters. Taking only the family and picking a size here
   * ignores the user's editor font size, and scaling that size again double-counts HiDPI, because
   * scheme sizes are already scaled.
   */
  fun editor(): Font =
    EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

  /**
   * The font a feedback note is written in: the UI family at the editor's size.
   *
   * A note is prose, not code, so it keeps the proportional UI family - a monospaced paragraph in a
   * narrow docked panel reads badly. The size comes from the editor because that is the setting a
   * user reaches for when text in the IDE is too small, and the note is the card's main content;
   * leaving it on the UI size made the note the one thing on screen that would not grow.
   */
  fun note(): Font = JBFont.regular().deriveFont(editor().size2D)
}
