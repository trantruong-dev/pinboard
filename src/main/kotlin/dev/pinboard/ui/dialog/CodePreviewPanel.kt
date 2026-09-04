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
import dev.pinboard.capture.SelectionSnapshot
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Read-only preview of the selected code with line numbers starting at the real [startLine].
 * Lets the user confirm they selected the right block before submitting.
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
    return object : EditorTextField(document, project, fileType, false) {
      override fun createEditor(): EditorEx {
        val editor = super.createEditor()
        configureEditor(editor, startLine)
        return editor
      }
    }.also { it.isEnabled = false }
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
}