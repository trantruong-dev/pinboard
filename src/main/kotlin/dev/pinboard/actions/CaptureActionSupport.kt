package dev.pinboard.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import dev.pinboard.capture.AnchorRegistry
import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import dev.pinboard.ui.dialog.FeedbackInputDialog
import dev.pinboard.ui.dialog.InlineFeedbackPopup
import dev.pinboard.util.Ulid

/**
 * Shared logic for both capture actions: build the snapshot off the EDT, ask for a note, then
 * persist to the store. Keeps the two action classes thin.
 */
object CaptureActionSupport {

  private val LOG = Logger.getInstance(CaptureActionSupport::class.java)

  /**
   * Captures with the balloon anchored over [editor].
   *
   * This is the path a selection takes. The note is written next to the code it is about, so the
   * user can keep reading instead of dismissing a window that covers what they were looking at.
   */
  fun captureInline(
    project: Project,
    editor: Editor,
    scope: Scope,
    capture: () -> SelectionSnapshot?,
  ) = collect(project, capture) { snapshot, store ->
    // Anchor before the balloon opens, not after it is submitted.
    //
    // The balloon is deliberately non-modal - the editor stays live so the code being pinned can be
    // re-read while writing the note. That means the file can change under it, and by submit time
    // the snapshot's line numbers may point at different code. Creating the marker now ties the pin
    // to the text the snapshot actually describes; the write-back then corrects the stored lines.
    val id = Ulid.generate()
    anchor(project, editor.document, id, snapshot)

    InlineFeedbackPopup.show(
      editor,
      snapshot,
      onCancel = { AnchorRegistry.getInstance(project).release(id) },
    ) { note ->
      store.add(build(id, scope, note, snapshot))
    }
  }

  /**
   * Captures with the modal dialog.
   *
   * Used where there is no caret to anchor to - pinning a whole file from the project tree or a
   * tab. Both paths share [dev.pinboard.ui.dialog.FeedbackForm], so a note means the same thing
   * whichever one produced it.
   */
  fun capture(
    project: Project,
    scope: Scope,
    capture: () -> SelectionSnapshot?,
  ) = collect(project, capture) { snapshot, store ->
    val dialog = FeedbackInputDialog(project, snapshot)
    if (dialog.showAndGet()) {
      val note = dialog.note.trim()
      if (note.isNotEmpty()) store.add(build(Ulid.generate(), scope, note, snapshot))
    }
  }

  /**
   * Runs [capture] on a background thread, then hands the snapshot to [ask] on the EDT.
   * [capture] must be cheap and ReadAction-guarded internally.
   */
  private fun collect(
    project: Project,
    capture: () -> SelectionSnapshot?,
    ask: (SelectionSnapshot?, FeedbackStore) -> Unit,
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
        ask(snapshot, store)
      }
    }
  }

  /**
   * Anchors an id at the range the snapshot describes.
   *
   * This is the one point where the exact range is known, so nothing has to be searched for: the
   * document is open and the offsets are the ones the user selected. Re-anchoring later goes through
   * [AnchorRegistry.ensureAnchored], which has to find the snippet again and can legitimately fail.
   */
  private fun anchor(project: Project, document: Document, id: String, snapshot: SelectionSnapshot?) {
    val startLine = snapshot?.startLine ?: return
    val endLine = snapshot.endLine ?: return
    AnchorRegistry.getInstance(project).anchorAt(id, document, startLine, endLine)
  }

  private fun build(id: String, scope: Scope, note: String, snapshot: SelectionSnapshot?): Feedback {
    val now = System.currentTimeMillis()
    return Feedback(
      id = id,
      status = Status.PENDING,
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
  }
}
