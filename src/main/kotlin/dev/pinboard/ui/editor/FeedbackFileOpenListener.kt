package dev.pinboard.ui.editor

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import dev.pinboard.capture.AnchorRegistry

/**
 * Decorates a file the moment it opens.
 *
 * [FeedbackHighlighter] only ever paints editors that exist when it runs, and it runs on store
 * changes. Reopening a file the user pinned in an earlier session changes nothing in the store, so
 * without this the pins would be invisible until the next unrelated queue edit.
 */
class FeedbackFileOpenListener : FileEditorManagerListener {

  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    val project = source.project
    if (project.isDisposed) return
    // Re-anchor first: markers die with their document, so a reopened file has none, and
    // painting before this would use the lines the item was pinned at rather than where its
    // code has since moved to.
    FileDocumentManager.getInstance().getDocument(file)?.let {
      AnchorRegistry.getInstance(project).ensureAnchored(it)
    }
    FeedbackHighlighter.getInstance(project).refresh()
  }
}
