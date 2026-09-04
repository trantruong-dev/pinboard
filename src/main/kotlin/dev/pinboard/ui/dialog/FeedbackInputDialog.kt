package dev.pinboard.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import dev.pinboard.capture.SelectionSnapshot
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Modal fallback for captures with no caret to anchor a balloon to - pinning a whole file from the
 * project tree or a tab.
 *
 * The body is [FeedbackForm], the same component the balloon uses, so the two capture paths cannot
 * drift apart in what they show or what they accept.
 */
class FeedbackInputDialog(
  project: Project,
  snapshot: SelectionSnapshot?,
) : DialogWrapper(project, false) {

  private val form = FeedbackForm(snapshot)

  init {
    title = "Pin for Agent"
    init()
    rootPane?.registerKeyboardAction(
      { doOKAction() },
      KeyStroke.getKeyStroke("ctrl ENTER"),
      JComponent.WHEN_IN_FOCUSED_WINDOW,
    )
  }

  override fun createCenterPanel(): JComponent = form

  override fun getPreferredFocusedComponent(): JComponent = form.noteArea

  override fun doValidate(): ValidationInfo? =
    if (form.hasNote()) null else ValidationInfo("Note cannot be empty", form.noteArea)

  val note: String get() = form.note()
}
