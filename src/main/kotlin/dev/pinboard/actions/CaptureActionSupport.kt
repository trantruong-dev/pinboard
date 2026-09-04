package dev.pinboard.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.store.FeedbackStore
import dev.pinboard.ui.dialog.FeedbackInputDialog
import dev.pinboard.util.Ulid

/**
 * Shared logic for both capture actions: build the snapshot off the EDT, open the input dialog,
 * then persist to the store. Keeps the two action classes thin.
 */
object CaptureActionSupport {

  private val LOG = Logger.getInstance(CaptureActionSupport::class.java)

  /**
   * Runs [capture] on a background thread, then shows the dialog on the EDT.
   * [capture] must be cheap and ReadAction-guarded internally.
   */
  fun capture(
    project: Project,
    scope: Scope,
    capture: () -> SelectionSnapshot?,
  ) {
    val store = FeedbackStore.getInstance(project)
    ApplicationManager.getApplication().executeOnPooledThread {
      val snapshot = try {
        capture()
      } catch (t: Throwable) {
        LOG.warn("capture failed", t)
        null
      }
      ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        val dialog = FeedbackInputDialog(project, snapshot)
        if (dialog.showAndGet()) {
          val note = dialog.note.trim()
          val now = System.currentTimeMillis()
          store.add(
            Feedback(
              id = Ulid.generate(),
              status = dev.pinboard.model.Status.PENDING,
              scope = scope,
              note = note,
              filePath = snapshot?.filePath,
              language = snapshot?.language,
              startLine = snapshot?.startLine,
              endLine = snapshot?.endLine,
              codeSnapshot = snapshot?.codeSnapshot,
              contentSha256 = snapshot?.contentSha256,
              truncated = snapshot?.truncated ?: false,
              symbolPath = snapshot?.symbolPath,
              vcsRevision = snapshot?.vcsRevision,
              thread = emptyList(),
              createdAt = now,
              updatedAt = now,
            )
          )
        }
      }
    }
  }
}