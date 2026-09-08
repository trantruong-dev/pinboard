package dev.pinboard.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
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
 * The one Copy entry, holding both copies as a submenu the way [ClearFeedbackActionGroup] holds
 * its three.
 *
 * Two Copy entries side by side could only be told apart by their labels, and the toolbar shows
 * icons alone - two identical glyphs, on the bar this panel already treats as the first thing to be
 * clipped when the tool window is narrow. Folding them into one entry costs a hover and buys back
 * a slot.
 */
class CopyFeedbackActionGroup(
  selection: FeedbackNodeSelection,
  supplier: PendingFeedbackSupplier,
) : DefaultActionGroup("Copy", true) {

  init {
    templatePresentation.icon = AllIcons.Actions.Copy
    templatePresentation.description = "Copy this pin, or the whole pending queue, as Markdown"
    add(CopyFeedbackAction(selection))
    add(CopyAllPendingAction(supplier))
  }
}

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

/**
 * Supplies the pending items to [CopyAllPendingAction].
 *
 * Separate from [FeedbackNodeSelection] because this is not a selection: the batch is whatever is
 * pending, not whatever the user clicked.
 */
interface PendingFeedbackSupplier {
  fun pending(): List<FeedbackItemNode>

  /** Asked on every toolbar tick, so it must not cost what building the list costs. */
  fun hasPending(): Boolean
}

/**
 * Copies the whole pending queue as one Markdown document.
 *
 * The point of the queue is handing a cluster over, and an agent without MCP access to it can only
 * be handed one by paste. Doing that today means copying pins one at a time.
 *
 * PENDING only. ACKNOWLEDGED means an agent already has the item, so including it would hand the
 * same work over twice.
 *
 * No shortcut, deliberately. Ctrl+C is the single-item copy, and this puts every note and every
 * captured snippet - secrets included, if the pinned lines held any - on the clipboard at once.
 * That is worth a menu entry, not a reflex.
 */
class CopyAllPendingAction(
  private val supplier: PendingFeedbackSupplier,
) : AnAction("Copy All Pending", "Copy every pending pin as Markdown", AllIcons.Actions.Copy) {

  override fun getActionUpdateThread() = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = supplier.hasPending()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val text = FeedbackMarkdown.renderAll(supplier.pending())
    // Nothing pending means nothing to hand over. Wiping the clipboard is not what was asked for.
    if (text.isEmpty()) return
    CopyPasteManager.getInstance().setContents(StringSelection(text))
  }
}
