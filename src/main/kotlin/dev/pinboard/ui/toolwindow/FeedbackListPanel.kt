package dev.pinboard.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.pinboard.model.Feedback
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackListener
import dev.pinboard.store.FeedbackStore
import dev.pinboard.mcp.StaleDetector
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The "Pinboard" ToolWindow content: grouped queue on top, detail below.
 *
 * Updates are push-based. The store publishes [FeedbackListener.TOPIC] on every mutation, whether
 * it came from the UI or from an MCP tool call, so an agent resolving an item is reflected here
 * without polling.
 */
class FeedbackListPanel(
  private val project: Project,
  private val content: Content,
  parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true), Disposable, FeedbackListener {

  private val store = FeedbackStore.getInstance(project)
  private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode("root")))
  private val detail: FeedbackDetailPanel
  private val pendingLabel = JBLabel()
  private val connectionChip = ConnectionChip()

  /**
   * Rebuilds run on a pooled thread and are coalesced: a batch of agent updates would otherwise
   * trigger one full rebuild per item.
   */
  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  /**
   * Own disposal flag. `Disposer.isDisposed` is deprecated (it cannot be queried reliably), so the
   * panel tracks its own state - rebuilds hop threads and must not touch a disposed UI.
   */
  @Volatile
  private var disposed = false

  @Volatile
  private var lastPendingCount = 0

  init {
    Disposer.register(parentDisposable, this)
    detail = FeedbackDetailPanel(project, this)

    tree.isRootVisible = false
    tree.showsRootHandles = true
    tree.cellRenderer = FeedbackCellRenderer()
    tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    tree.addTreeSelectionListener { detail.show(selectedNode()) }
    installNavigation()
    installRowActions()

    toolbar = buildToolbar()
    setContent(buildContent())

    project.messageBus.connect(this).subscribe(FeedbackListener.TOPIC, this)
    onChanged()  // initial load
  }

  /**
   * Called from the store while it holds its lock, on whichever thread mutated - the EDT for UI
   * actions, a Ktor coroutine thread for MCP tools. Must return immediately and must not touch
   * the store again, so it only arms the coalescing alarm.
   */
  override fun onChanged() {
    if (disposed) return
    alarm.cancelAllRequests()
    alarm.addRequest({ rebuildOffEdt() }, COALESCE_MS)
  }

  /**
   * The queue over the connection footer.
   *
   * The footer owns the only timer here and pushes each refresh into the toolbar chip, so the two
   * readings of "is the agent talking to us" come from one evaluation and cannot disagree.
   */
  private fun buildContent(): JComponent {
    val footer = FooterStatusPanel(project, this) { connectionChip.update(it) }
    return JPanel(BorderLayout()).apply {
      add(buildSplitter(), BorderLayout.CENTER)
      add(footer, BorderLayout.SOUTH)
    }
  }

  private fun buildSplitter(): JComponent {
    // Stacked, not side by side: this tool window docks right and is usually narrow, where a
    // horizontal split leaves neither half readable.
    val splitter = OnePixelSplitter(true, 0.55f)
    splitter.firstComponent = JBScrollPane(tree)
    splitter.secondComponent = detail
    return splitter
  }

  /**
   * Delete is reachable three ways on purpose: the toolbar button, the Del key, and the row's own
   * context menu. A docked tool window is narrow enough that the toolbar can be the first thing
   * clipped, and right-clicking a row is what the platform trains users to try.
   */
  private fun buildActionGroup() = DefaultActionGroup(
    DeleteFeedbackAction(project, FeedbackSelection { selectedFeedback() }),
    DeleteAllFeedbackAction(project),
  )

  private fun installRowActions() {
    val group = buildActionGroup()
    PopupHandler.installPopupMenu(tree, group, ActionPlaces.TOOLWINDOW_POPUP)
    DeleteFeedbackAction(project, FeedbackSelection { selectedFeedback() })
      .registerCustomShortcutSet(CommonShortcuts.getDelete(), tree, this)
  }

  private fun buildToolbar(): JComponent {
    val group = buildActionGroup()
    val actionToolbar = ActionManager.getInstance()
      .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
    actionToolbar.targetComponent = tree

    // No right border: the FlowLayout below already spaces the badge from the chip.
    pendingLabel.foreground = UIUtil.getContextHelpForeground()

    val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(2)))
    right.isOpaque = false
    right.add(pendingLabel)
    right.add(connectionChip)

    val bar = JPanel(BorderLayout())
    bar.add(actionToolbar.component, BorderLayout.WEST)
    bar.add(right, BorderLayout.EAST)
    return bar
  }

  /** Double-click and Enter jump to source; single click only drives the detail panel. */
  private fun installNavigation() {
    object : DoubleClickListener() {
      override fun onDoubleClick(event: MouseEvent): Boolean {
        val feedback = selectedFeedback() ?: return false
        FeedbackNavigator.navigate(project, feedback)
        return true
      }
    }.installOn(tree)

    tree.registerKeyboardAction(
      { selectedFeedback()?.let { FeedbackNavigator.navigate(project, it) } },
      KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
      JComponent.WHEN_FOCUSED,
    )
  }

  private fun rebuildOffEdt() {
    if (disposed || project.isDisposed) return
    val items = store.all()
    // Staleness hits the VFS, so it is resolved once here rather than inside the cell renderer.
    val stale = items.associate { it.id to flagsFor(it) }
    val model = FeedbackTreeModel.build(items, stale)
    val pending = items.count { it.status == Status.PENDING }
    ApplicationManager.getApplication().invokeLater(
      { applyModel(model, pending) },
      ModalityState.any(),
    )
  }

  private fun flagsFor(feedback: Feedback): StaleFlags {
    val result = StaleDetector.check(project, feedback)
    return StaleFlags(stale = result.stale, fileMissing = result.fileMissing)
  }

  private fun applyModel(model: DefaultTreeModel, pending: Int) {
    if (disposed || project.isDisposed) return
    val previouslySelected = selectedFeedback()?.id

    tree.model = model
    expandDefaultGroups(model)
    restoreSelection(model, previouslySelected)

    lastPendingCount = pending
    pendingLabel.text = if (pending > 0) "Pending: $pending" else ""
    content.displayName = if (pending > 0) "Feedback ($pending)" else "Feedback"
  }

  private fun expandDefaultGroups(model: DefaultTreeModel) {
    val root = model.root as DefaultMutableTreeNode
    for (i in 0 until root.childCount) {
      val groupNode = root.getChildAt(i) as DefaultMutableTreeNode
      val group = groupNode.userObject as? StatusGroupNode ?: continue
      if (group.status !in FeedbackTreeModel.COLLAPSED_BY_DEFAULT) {
        tree.expandPath(TreePath(groupNode.path))
      }
    }
  }

  /** Reselects the same item after a rebuild so an agent update does not move the user's cursor. */
  private fun restoreSelection(model: DefaultTreeModel, feedbackId: String?) {
    val target = feedbackId?.let { findNode(model, it) }
    if (target == null) {
      tree.clearSelection()
      detail.show(null)
      return
    }
    val path = TreePath(target.path)
    tree.expandPath(path.parentPath)
    tree.selectionPath = path
    tree.scrollPathToVisible(path)
  }

  private fun findNode(model: DefaultTreeModel, feedbackId: String): DefaultMutableTreeNode? {
    val root = model.root as DefaultMutableTreeNode
    for (i in 0 until root.childCount) {
      val groupNode = root.getChildAt(i) as DefaultMutableTreeNode
      for (j in 0 until groupNode.childCount) {
        val itemNode = groupNode.getChildAt(j) as DefaultMutableTreeNode
        val item = itemNode.userObject as? FeedbackItemNode ?: continue
        if (item.feedback.id == feedbackId) return itemNode
      }
    }
    return null
  }

  private fun selectedNode(): FeedbackItemNode? =
    (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FeedbackItemNode

  private fun selectedFeedback(): Feedback? = selectedNode()?.feedback

  /** Test hook: runs a rebuild synchronously instead of waiting on the alarm. */
  fun rebuildNowForTest() {
    rebuildOffEdt()
  }

  /** Test hook: pending count last rendered into the badge. */
  fun pendingCountForTest(): Int = lastPendingCount

  /** Test hook: exposes the tree so tests can assert structure and selection. */
  fun treeForTest(): Tree = tree

  override fun dispose() {
    disposed = true
    // Children (detail panel, alarm) are registered under this and disposed by the platform.
  }

  private companion object {
    const val COALESCE_MS = 100
  }
}
