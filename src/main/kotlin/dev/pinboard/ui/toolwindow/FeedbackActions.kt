package dev.pinboard.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.store.FeedbackStore
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
    val line = ((feedback.startLine ?: 1) - 1).coerceAtLeast(0)
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
