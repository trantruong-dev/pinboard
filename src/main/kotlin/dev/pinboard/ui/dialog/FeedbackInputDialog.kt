package dev.pinboard.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.Row
import dev.pinboard.capture.SelectionSnapshot
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Dialog to type a feedback note and preview the selected code.
 * Layout top-down: file path + line range (dim label), code preview, note text area.
 * Ctrl+Enter submits, Esc cancels, empty note blocks submission.
 */
class FeedbackInputDialog(
  private val project: Project,
  private val snapshot: SelectionSnapshot?,
) : DialogWrapper(project, false) {

  private val noteArea = JBTextArea(6, 60).apply {
    lineWrap = true
    wrapStyleWord = true
  }
  private val previewPanel = CodePreviewPanel(project, snapshot)

  init {
    title = "Pin for Agent"
    isOKActionEnabled = true
    init()
    // Bind the preview editor to this dialog's disposable so the editor + document are released
    // when the dialog closes (no per-open leak).
    previewPanel.bindToDisposable(disposable)
    rootPane?.registerKeyboardAction(
      { doOKAction() },
      KeyStroke.getKeyStroke("ctrl ENTER"),
      JComponent.WHEN_IN_FOCUSED_WINDOW,
    )
  }

  override fun createCenterPanel(): JComponent {
    val location = buildLocationLabel()
    val root = panel {
      row {
        cell(location)
      }
      row {
        cell(previewPanel)
          .align(Align.FILL)
          .resizableColumn()
      }.resizableRow()
      row {
        label("Note:")
      }
      row {
        cell(noteArea)
          .align(Align.FILL)
          .resizableColumn()
      }.resizableRow()
    }
    root.preferredSize = Dimension(560, 420)
    return root
  }

  private fun buildLocationLabel(): JComponent {
    val path = snapshot?.filePath ?: "whole project"
    val range = if (snapshot?.startLine != null) {
      "  ${snapshot.startLine}-${snapshot.endLine}"
    } else {
      ""
    }
    val label = JLabel("$path$range")
    label.isOpaque = false
    return label
  }

  override fun getPreferredFocusedComponent(): JComponent = noteArea

  override fun doValidate(): ValidationInfo? {
    return if (noteArea.text.isBlank()) {
      ValidationInfo("Note cannot be empty", noteArea)
    } else {
      null
    }
  }

  val note: String get() = noteArea.text
}