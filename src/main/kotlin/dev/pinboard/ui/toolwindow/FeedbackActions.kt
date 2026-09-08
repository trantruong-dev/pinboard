package dev.pinboard.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.pinboard.capture.AnchorRegistry
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import dev.pinboard.ui.dialog.FeedbackInputDialog
import dev.pinboard.ui.toSnapshot
import dev.pinboard.util.ProjectFiles

/** Supplies the currently selected feedback item to the toolbar actions. */
fun interface FeedbackSelection {
  fun selected(): Feedback?
}

/**
 * Opens the file a feedback item points at and puts the caret on the captured start line.
 *
 * Line numbers are stored 1-based (that is what the user saw in the gutter) while
 * [OpenFileDescriptor] is 0-based, hence the shift. A deleted file reports a notification rather
 * than throwing - the row already carries a "file missing" badge, this just explains the no-op.
 */
object FeedbackNavigator {

  fun navigate(project: Project, feedback: Feedback) {
    if (feedback.scope == Scope.PROJECT) return
    val relativePath = feedback.filePath ?: return
    val file = ProjectFiles.resolve(project, relativePath)
    if (file == null) {
      notifyMissing(project, relativePath)
      return
    }
    // Prefer where the code is now over where it was pinned, so a jump after an edit above
    // the pin still lands on the right lines.
    val anchored = AnchorRegistry.getInstance(project).lineRange(feedback.id)?.first
    val line = ((anchored ?: feedback.startLine ?: 1) - 1).coerceAtLeast(0)
    OpenFileDescriptor(project, file, line, 0).navigate(true)
  }

  private fun notifyMissing(project: Project, relativePath: String) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP)
      .createNotification("File no longer exists: $relativePath", NotificationType.WARNING)
      .notify(project)
  }

  const val NOTIFICATION_GROUP = "Pinboard"
}

/**
 * Reopens a pinned note so the wording can be fixed.
 *
 * PENDING only. Once the agent has acknowledged an item it is working from the words it read, and
 * rewriting them underneath it is the reliable way to have the two of you acting on different
 * instructions. The greyed-out button here is only the visible half of that rule - the half that
 * has to hold is in [FeedbackStore.updateNote], because the agent can acknowledge while this
 * dialog is open.
 *
 * Only the note is editable. The range, the snapshot and the symbol path are what was pinned, and
 * changing where a note points is a different pin.
 */
class EditFeedbackAction(
  private val project: Project,
  private val selection: FeedbackSelection,
) : AnAction("Edit", "Change the note on this pin", AllIcons.Actions.Edit) {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = selection.selected()?.status == Status.PENDING
  }

  override fun actionPerformed(e: AnActionEvent) {
    val feedback = selection.selected() ?: return
    if (feedback.status != Status.PENDING) return
    val dialog = FeedbackInputDialog(
      project = project,
      snapshot = feedback.toSnapshot(),
      initialNote = feedback.note,
      dialogTitle = "Edit Pin",
      okText = "Save",
    )
    if (!dialog.showAndGet()) return
    // Same guard the capture path carries, and for the same reason: the dialog's Ctrl+Enter calls
    // doOKAction() directly, while validation runs on a delayed alarm, so a note emptied and
    // submitted inside that window gets through. Blank means "never mind", not an error - handling
    // it here is also what keeps the notification below able to name its one real cause.
    if (dialog.note.isEmpty()) return
    // Refused, with a non-blank note, means the item stopped being PENDING while the dialog was
    // open. Say so: the user typed those words and is entitled to know they did not land.
    if (FeedbackStore.getInstance(project).updateNote(feedback.id, dialog.note) == null) {
      NotificationGroupManager.getInstance()
        .getNotificationGroup(FeedbackNavigator.NOTIFICATION_GROUP)
        .createNotification(
          "The agent picked this item up while you were editing. Your change was not saved.",
          NotificationType.WARNING,
        )
        .notify(project)
    }
  }
}

/** Removes one feedback item. Human-only: the agent has no delete tool. */
class DeleteFeedbackAction(
  private val project: Project,
  private val selection: FeedbackSelection,
) : AnAction("Delete", "Remove this feedback item", AllIcons.General.Remove) {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = selection.selected() != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val feedback = selection.selected() ?: return
    FeedbackStore.getInstance(project).delete(feedback.id)
  }
}

/**
 * The "Clear" dropdown.
 *
 * A popup group rather than one Delete All button so the finished work can be cleared without
 * touching the queue. Each entry shows a live count and disables itself when empty, which is what
 * makes it safe to open and read - the user can see what a click would remove before clicking it.
 */
class ClearFeedbackActionGroup(project: Project) : DefaultActionGroup("Clear", true) {

  init {
    templatePresentation.icon = AllIcons.Actions.GC
    templatePresentation.description = "Remove finished feedback, or the whole queue"
    add(ClearByStatusAction(project, Status.RESOLVED))
    add(ClearByStatusAction(project, Status.DISMISSED))
    add(DeleteAllFeedbackAction(project))
  }
}

/** Removes every item in one finished status. Not confirmed: the work it deletes is already done. */
private class ClearByStatusAction(
  private val project: Project,
  private val status: Status,
) : AnAction() {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val count = FeedbackStore.getInstance(project).countByStatus(status)
    e.presentation.text = "Clear ${status.name.lowercase()} ($count)"
    e.presentation.isEnabled = count > 0
  }

  override fun actionPerformed(e: AnActionEvent) {
    // One write, not one per item: clearing a long history item by item rewrites the store file
    // once for each one.
    FeedbackStore.getInstance(project).deleteByStatus(status)
  }
}

/** Clears the whole queue. Confirmed because it is not undoable. */
class DeleteAllFeedbackAction(
  private val project: Project,
) : AnAction("Delete All", "Remove every feedback item in this project", AllIcons.Actions.GC) {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = FeedbackStore.getInstance(project).all().isNotEmpty()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val store = FeedbackStore.getInstance(project)
    val count = store.all().size
    if (count == 0) return
    val answer = Messages.showYesNoDialog(
      project,
      "Delete all $count feedback item(s)? This cannot be undone.",
      "Delete All Feedback",
      Messages.getWarningIcon(),
    )
    if (answer == Messages.YES) store.deleteAll()
  }
}
