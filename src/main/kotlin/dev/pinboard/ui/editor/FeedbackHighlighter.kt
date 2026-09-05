package dev.pinboard.ui.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColorUtil
import dev.pinboard.capture.AnchorRegistry
import dev.pinboard.model.Feedback
import dev.pinboard.store.FeedbackStore
import dev.pinboard.ui.theme.PinboardColors
import dev.pinboard.ui.toolwindow.StatusAppearance
import dev.pinboard.util.ProjectFiles
import javax.swing.Icon

/**
 * Marks pinned code in the editor: a faint line tint, a mark in the error stripe, and a pin in the
 * gutter that focuses the tool window.
 *
 * Without this the editor forgets. You pin five things while reading a file, scroll away, and there
 * is nothing on the page to say which lines you marked - the queue knows, but the code does not.
 *
 * Only open items are decorated. A resolved one is history and would just be visual noise over code
 * that has already been dealt with.
 */
@Service(Service.Level.PROJECT)
class FeedbackHighlighter(private val project: Project) : Disposable {

  /**
   * Keyed by editor, not by document.
   *
   * Highlighters are added to an editor's own markup model, and one file open in a split has
   * two editors sharing a document. Keyed by document, the second editor's pass would clear
   * the highlighters the first had just been given, and only the last editor would show them.
   */
  private val installed = mutableMapOf<Editor, MutableList<RangeHighlighter>>()

  fun refresh() {
    if (project.isDisposed) return
    ApplicationManager.getApplication().assertIsDispatchThread()

    val byFile = openItemsByFile()
    val documentManager = FileDocumentManager.getInstance()
    val editors = EditorFactory.getInstance().allEditors.filter { it.project === project }

    // An editor that has gone away takes its markup model with it, so its entry here is only a
    // reference nobody can reach.
    val live = editors.toSet()
    installed.keys.retainAll(live)

    for (editor in editors) {
      val document = editor.document
      val file = documentManager.getFile(document) ?: continue

      // Always clear first: an item that was resolved, deleted or relocated must not leave its old
      // decoration behind, and a stale RangeHighlighter holds a reference into the markup model.
      installed.remove(editor)?.forEach { it.dispose() }

      val items = byFile[file] ?: continue
      val added = mutableListOf<RangeHighlighter>()
      for (feedback in items) {
        val range = offsetsFor(document, feedback) ?: continue
        val attributes = TextAttributes().apply {
          backgroundColor = ColorUtil.withAlpha(StatusAppearance.color(feedback.status), 0.10)
          errorStripeColor = StatusAppearance.color(feedback.status)
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
          range.first,
          range.second,
          // Below the selection layer: the user's selection must stay the strongest thing on screen.
          HighlighterLayer.SELECTION - 1,
          attributes,
          HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.errorStripeTooltip = feedback.note
        highlighter.gutterIconRenderer = PinGutterRenderer(project, feedback)
        added += highlighter
      }
      if (added.isNotEmpty()) installed[editor] = added
    }
  }

  private fun openItemsByFile(): Map<VirtualFile, List<Feedback>> =
    FeedbackStore.getInstance(project).all()
      .asSequence()
      .filter { StatusAppearance.isOpen(it.status) }
      .mapNotNull { feedback ->
        val path = feedback.filePath ?: return@mapNotNull null
        val file = ProjectFiles.resolve(project, path) ?: return@mapNotNull null
        file to feedback
      }
      .groupBy({ it.first }, { it.second })

  /**
   * Stored lines are 1-based and inclusive; document lines are 0-based. Everything is clamped
   * because the file may have shrunk since the item was pinned - that is a stale item, not a reason
   * to throw inside a UI refresh.
   */
  private fun offsetsFor(document: Document, feedback: Feedback): Pair<Int, Int>? {
    // The anchor is where the code is now; the stored lines are where it was pinned. Painting
    // from the stored lines would leave the tint behind after any edit above the pin.
    val anchored = AnchorRegistry.getInstance(project).lineRange(feedback.id)
    val startLine = anchored?.first ?: feedback.startLine ?: return null
    val endLine = anchored?.second ?: feedback.endLine ?: startLine
    if (document.lineCount == 0) return null
    val first = (startLine - 1).coerceIn(0, document.lineCount - 1)
    val last = (endLine - 1).coerceIn(first, document.lineCount - 1)
    return document.getLineStartOffset(first) to document.getLineEndOffset(last)
  }

  override fun dispose() {
    installed.values.flatten().forEach { it.dispose() }
    installed.clear()
  }

  /** The pin in the gutter. Clicking it brings the queue forward with the note in it. */
  private class PinGutterRenderer(
    private val project: Project,
    private val feedback: Feedback,
  ) : GutterIconRenderer() {

    override fun getIcon(): Icon = dev.pinboard.ui.toolwindow.PinboardIcons.Pin

    override fun getTooltipText(): String = feedback.note

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
      }
    }

    // The platform pools gutter renderers by equality; without these, every refresh would be treated
    // as a brand new icon and the gutter would flicker.
    override fun equals(other: Any?): Boolean =
      other is PinGutterRenderer && other.feedback.id == feedback.id

    override fun hashCode(): Int = feedback.id.hashCode()

    private companion object {
      const val TOOL_WINDOW_ID = "Pinboard"
    }
  }

  companion object {
    fun getInstance(project: Project): FeedbackHighlighter =
      project.getService(FeedbackHighlighter::class.java)
  }
}

/**
 * Paints a barely-there wash on the tab of any file that still has open feedback, so a file with
 * work owed on it is identifiable without opening it.
 *
 * The platform asks this for every tab whenever the tab strip repaints, so it compares the file's
 * relative path against the stored ones rather than resolving each stored path back through the VFS.
 */
class FeedbackTabColorProvider : com.intellij.openapi.fileEditor.impl.EditorTabColorProvider {

  override fun getEditorTabColor(project: Project, file: VirtualFile): java.awt.Color? {
    val relativePath = ProjectFiles.relativePath(project, file) ?: return null
    // Stored paths are canonicalised on write and migrated on load, so they can be compared as
    // they are. This runs for every tab on every tab-strip repaint, so it must not sort the
    // queue or allocate per item.
    val hasOpen = FeedbackStore.getInstance(project)
      .hasOpenItemFor(relativePath, StatusAppearance::isOpen)
    return if (hasOpen) ColorUtil.withAlpha(PinboardColors.statusPending, 0.10) else null
  }
}
