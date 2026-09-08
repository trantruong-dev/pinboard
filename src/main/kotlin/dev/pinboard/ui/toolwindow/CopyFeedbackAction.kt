package dev.pinboard.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import javax.swing.text.JTextComponent

/**
 * Supplies the selected row to [CopyFeedbackAction].
 *
 * Takes the node rather than the [dev.pinboard.model.Feedback] that [FeedbackSelection] hands out,
 * because the stale and file-missing flags are resolved during a rebuild and live only on the node.
 */
fun interface FeedbackNodeSelection {
  fun selected(): FeedbackItemNode?
}

/**
 * Text highlighted inside [component], or null when there is none.
 *
 * Split out of the action so it can be asserted without building an `AnActionEvent`, and typed as
 * `Any?` because that is what a data key hands back.
 */
internal fun selectedTextIn(component: Any?): String? =
  (component as? JTextComponent)?.selectedText?.takeIf { it.isNotEmpty() }

/**
 * Copies the pin, or the highlighted text when there is some.
 *
 * One entry rather than two. Which of the two it does is decided by the component the menu was
 * opened on, and said in the label, because being handed the whole item after carefully dragging
 * over one line is the one thing a Copy entry must not do. `PopupHandler` sets the component it was
 * installed on as the popup's target, so a right-click inside a note resolves to that note's pane;
 * the toolbar and the row menu target the list, which has no text selection, so both copy the item.
 *
 * Not a `CopyProvider` / `DataProvider`: that API churns between platform 252 and 262 and this
 * plugin has to compile against the whole range from one source tree.
 */
class CopyFeedbackAction(
  private val selection: FeedbackNodeSelection,
) : AnAction("Copy", "Copy this pin as Markdown", AllIcons.Actions.Copy) {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val highlighted = highlightedText(e)
    e.presentation.isEnabled = highlighted != null || selection.selected() != null
    // The gesture is identical either way, so the label is the only place the difference can show.
    e.presentation.text = if (highlighted != null) "Copy Selection" else "Copy"
    e.presentation.description =
      if (highlighted != null) "Copy the highlighted text" else "Copy this pin as Markdown"
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = highlightedText(e)
      ?: selection.selected()?.let { FeedbackMarkdown.render(it) }
      ?: return
    CopyPasteManager.getInstance().setContents(StringSelection(text))
  }

  private fun highlightedText(e: AnActionEvent): String? =
    selectedTextIn(e.getData(PlatformDataKeys.CONTEXT_COMPONENT))
}
