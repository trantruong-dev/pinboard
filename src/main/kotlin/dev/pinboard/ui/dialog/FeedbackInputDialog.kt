package dev.pinboard.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import dev.pinboard.capture.SelectionSnapshot
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Modal for the note-writing that has no caret to anchor a balloon to: pinning a whole file from
 * the project tree or a tab, and reopening an existing note from the tool window to edit it.
 *
 * The body is [FeedbackForm], the same component the balloon uses, so the paths cannot drift apart
 * in what they show or what they accept. Only the frame differs, which is why the title and the OK
 * label are parameters rather than three near-identical dialogs.
 */
class FeedbackInputDialog(
  project: Project,
  snapshot: SelectionSnapshot?,
  initialNote: String = "",
  dialogTitle: String = "Pin for Agent",
  okText: String? = null,
) : DialogWrapper(project, false) {

  private val form = FeedbackForm(snapshot, initialNote = initialNote)

  init {
    title = dialogTitle
    // Before init(): the OK action exists from the constructor, and a label set before init() is
    // the only one that gets its mnemonic extracted.
    okText?.let { setOKButtonText(it) }
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
