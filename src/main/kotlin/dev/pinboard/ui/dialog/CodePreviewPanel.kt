package dev.pinboard.ui.dialog

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorGutter
import com.intellij.openapi.editor.EditorSettings
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import dev.pinboard.capture.SelectionSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Read-only preview of the selected code with line numbers starting at the real [startLine].
 * Lets the user confirm they selected the right block before submitting.
 *
 * Read-only here means viewer, not disabled. The editor stays live, so the snapshot can be
 * selected, copied and searched with Ctrl+F, and is painted at full contrast. A disabled editor
 * has none of that: no caret, no selection, no copy, and it paints dimmed.
 *
 * Several lines tall and scrollable, not a single-line field. Once the file has moved on, this
 * snapshot is the only record of what was actually pinned, so it has to be readable as a block.
 */
class CodePreviewPanel(
  private val project: Project,
  private val snapshot: SelectionSnapshot?,
) : JPanel(BorderLayout()) {

  private val editorField: EditorTextField? = build()

  init {
    if (editorField != null) {
      add(editorField, BorderLayout.CENTER)
    } else {
      add(createEmptyPreview(), BorderLayout.CENTER)
    }
  }

  /** Returns the preview editor so the owning dialog can bind it to its disposable. */
  fun bindToDisposable(disposable: com.intellij.openapi.Disposable) {
    editorField?.setDisposedWith(disposable)
  }

  private fun build(): EditorTextField? {
    val snapshot = snapshot ?: return null
    val code = snapshot.codeSnapshot ?: return null
    val fileType = resolveFileType(snapshot.language)
    val document = EditorFactory.getInstance().createDocument(code)
    val startLine = snapshot.startLine ?: 1
    // oneLineMode is the constructor's default and would show the first line and nothing else.
    return object : EditorTextField(document, project, fileType, /* isViewer = */ true, /* oneLineMode = */ false) {
      override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        configureEditor(editor, startLine)
        return editor
      }
    }
  }

  /**
   * Tall enough to read the pinned block, short enough to leave the note and the conversation on
   * screen with it.
   *
   * The detail panel stacks its sections at their preferred height, so an uncapped preview of a
   * long snapshot would push everything under it out of view. Past the cap the editor scrolls on
   * its own, which is what [configureEditor] keeps both scrollbars on for.
   */
  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    val max = JBUI.scale(MAX_HEIGHT)
    return if (size.height > max) Dimension(size.width, max) else size
  }

  private fun configureEditor(editor: EditorEx, startLine: Int) {
    val settings: EditorSettings = editor.settings
    settings.isLineNumbersShown = true
    settings.isLineMarkerAreaShown = false
    settings.isFoldingOutlineShown = false
    settings.isUseSoftWraps = false
    editor.setHorizontalScrollbarVisible(true)
    editor.setVerticalScrollbarVisible(true)
    // Shift line numbers so they start from the real startLine in the source file.
    // The platform passes 1-based line numbers into the converter, so offset by startLine - 1.
    val converter = object : LineNumberConverter {
      override fun convert(editor: com.intellij.openapi.editor.Editor, lineNumber: Int): Int? {
        return lineNumber + startLine - 1
      }

      override fun getMaxLineNumber(editor: com.intellij.openapi.editor.Editor): Int? {
        return editor.document.lineCount + startLine - 1
      }
    }
    editor.gutter.setLineNumberConverter(converter)
  }

  private fun resolveFileType(language: String?): FileType {
    if (language == null) return PlainTextFileType.INSTANCE
    val registered = FileTypeManager.getInstance().registeredFileTypes
    return registered.firstOrNull { it.name == language } ?: PlainTextFileType.INSTANCE
  }

  private fun createEmptyPreview(): JComponent {
    val label = JLabel("No code selected (whole file or project scope)")
    label.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    return label
  }

  private companion object {
    /** Roughly a dozen lines at the default editor font. */
    const val MAX_HEIGHT = 220
  }
}