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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.pinboard.mcp.StaleDetector
import dev.pinboard.model.Feedback
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackListener
import dev.pinboard.store.FeedbackStore
import dev.pinboard.ui.editor.FeedbackHighlighter
import dev.pinboard.ui.theme.PinboardColors
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The "Pinboard" ToolWindow content: progress ribbon, grouped queue, detail below, connection footer.
 *
 * Updates are push-based. The store publishes [FeedbackListener.TOPIC] on every mutation, whether
 * it came from the UI or from an MCP tool call, so an agent resolving an item is reflected here
 * without polling.
 */
class FeedbackListPanel(
  private val project: Project,
  private val content: Content,
  parentDisposable: Disposable,
  private val toolWindow: ToolWindow? = null,
) : SimpleToolWindowPanel(true, true), Disposable, FeedbackListener {

  private val store = FeedbackStore.getInstance(project)
  private val listModel = FeedbackListModel()
  private val list = JBList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = FeedbackCardRenderer()
    background = PinboardColors.surface
    // Cards already wrap to the list width, so the platform's hover popup for clipped rows would
    // only repaint the same card overflowing past the tool window edge.
    setExpandableItemsEnabled(false)
  }
  private val detail: FeedbackDetailPanel
  private val ribbon = ProgressRibbon()
  private val pendingLabel = JBLabel()
  private val connectionChip = ConnectionChip()
  private val badge = BadgeIconSupplier(PinboardIcons.ToolWindow)

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

    list.addListSelectionListener { if (!it.valueIsAdjusting) detail.show(selectedNode()) }
    installGroupFolding()
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
    val queue = JPanel(BorderLayout()).apply {
      background = PinboardColors.surface
      add(ribbon, BorderLayout.NORTH)
      add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }
    // Stacked, not side by side: this tool window docks right and is usually narrow, where a
    // horizontal split leaves neither half readable.
    val splitter = OnePixelSplitter(true, 0.55f)
    splitter.firstComponent = queue
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
    ClearFeedbackActionGroup(project),
  )

  private fun installRowActions() {
    val group = buildActionGroup()
    PopupHandler.installPopupMenu(list, group, ActionPlaces.TOOLWINDOW_POPUP)
    DeleteFeedbackAction(project, FeedbackSelection { selectedFeedback() })
      .registerCustomShortcutSet(CommonShortcuts.getDelete(), list, this)
  }

  /**
   * A list has no disclosure triangle of its own, so the header row is the control: clicking
   * anywhere on it folds the group. This is the one affordance the tree used to provide for free.
   */
  private fun installGroupFolding() {
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val index = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
        if (!list.getCellBounds(index, index).contains(e.point)) return
        val row = listModel.getElementAt(index) as? FeedbackRow.StatusHeader ?: return
        listModel.toggleCollapsed(row.status)
      }
    })
  }

  private fun buildToolbar(): JComponent {
    val group = buildActionGroup()
    val actionToolbar = ActionManager.getInstance()
      .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
    actionToolbar.targetComponent = list

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
    }.installOn(list)

    list.registerKeyboardAction(
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
    val pending = items.count { it.status == Status.PENDING }
    ApplicationManager.getApplication().invokeLater(
      { applyModel(items, stale, pending) },
      ModalityState.any(),
    )
  }

  private fun flagsFor(feedback: Feedback): StaleFlags {
    val result = StaleDetector.check(project, feedback)
    return StaleFlags(stale = result.stale, fileMissing = result.fileMissing)
  }

  private fun applyModel(items: List<Feedback>, stale: Map<String, StaleFlags>, pending: Int) {
    if (disposed || project.isDisposed) return
    val previouslySelected = selectedFeedback()?.id

    listModel.setItems(items, stale)
    restoreSelection(previouslySelected)
    ribbon.update(items)

    lastPendingCount = pending
    pendingLabel.text = if (pending > 0) "Pending: $pending" else ""
    content.displayName = if (pending > 0) "Feedback ($pending)" else "Feedback"
    // A dot on the stripe icon is the only signal that survives the tool window being collapsed.
    toolWindow?.setIcon(badge.getWarningIcon(pending > 0))

    FeedbackHighlighter.getInstance(project).refresh()
  }

  /** Reselects the same item after a rebuild so an agent update does not move the user's cursor. */
  private fun restoreSelection(feedbackId: String?) {
    val index = feedbackId?.let { indexOfFeedback(it) } ?: -1
    if (index < 0) {
      list.clearSelection()
      detail.show(null)
      return
    }
    list.selectedIndex = index
    list.ensureIndexIsVisible(index)
  }

  private fun indexOfFeedback(feedbackId: String): Int {
    for (i in 0 until listModel.size) {
      val row = listModel.getElementAt(i)
      if (row is FeedbackRow.Item && row.node.feedback.id == feedbackId) return i
    }
    return -1
  }

  private fun selectedNode(): FeedbackItemNode? =
    (list.selectedValue as? FeedbackRow.Item)?.node

  private fun selectedFeedback(): Feedback? = selectedNode()?.feedback

  /** Test hook: runs a rebuild synchronously instead of waiting on the alarm. */
  fun rebuildNowForTest() {
    rebuildOffEdt()
  }

  /** Test hook: pending count last rendered into the badge. */
  fun pendingCountForTest(): Int = lastPendingCount

  /** Test hook: exposes the list so tests can assert row structure and selection. */
  fun listForTest(): JBList<FeedbackRow> = list

  /** Test hook: folds a group the way clicking its header does. */
  fun toggleGroupForTest(status: Status) = listModel.toggleCollapsed(status)

  override fun dispose() {
    disposed = true
    // Children (detail panel, alarm) are registered under this and disposed by the platform.
  }

  private companion object {
    const val COALESCE_MS = 100
  }
}
