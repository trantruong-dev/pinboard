package dev.pinboard.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pinboard.mcp.McpActivity
import dev.pinboard.mcp.ToolsetHealth
import dev.pinboard.ui.theme.PinboardColors
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer

/**
 * One line at the bottom of the tool window saying what the agent last did, with the raw log folded
 * away behind a disclosure.
 *
 * The log is collapsed by default and the summary is a single line, because this panel is docked to
 * a narrow right-hand strip where every row it takes costs the queue a row. It is here at all
 * because the alternative, when an agent could not reach the tools, was reading `idea.log`.
 */
class FooterStatusPanel(
  private val project: Project,
  parent: Disposable,
  /** Notified on every refresh so the toolbar chip and this footer can never disagree. */
  private val onViewChanged: (ConnectionView) -> Unit = {},
) : JPanel(BorderLayout()) {

  private val activity = McpActivity.getInstance(project)

  private val dot = JBLabel("●")
  private val summary = JBLabel().apply {
    font = JBFont.small()
    foreground = PinboardColors.textMuted
  }
  private val toggle = JBLabel(COLLAPSED_LABEL).apply {
    font = JBFont.small()
    foreground = PinboardColors.accent
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  }

  private val logArea = JTextArea().apply {
    isEditable = false
    lineWrap = false
    font = JBFont.small()
  }
  private val logScroll = JBScrollPane(logArea).apply {
    isVisible = false
    preferredSize = Dimension(0, JBUI.scale(140))
  }

  /**
   * Repaints the age in the summary line. Nothing pushes elapsed time, so it has to be pulled; 2s is
   * fast enough that "just now" never lingers and slow enough to be free.
   *
   * The parent disposable outlives a hidden tool window, so this keeps firing with nobody looking.
   * Skipping the work while off-screen is cheaper than tearing the timer up and down on every
   * visibility change, and the first tick after it reappears repaints it.
   */
  private val ticker = Timer(REFRESH_MS) { if (isShowing) refresh() }

  /**
   * Tracks this panel's own lifetime, not the project's. The tool window can be unregistered while
   * the project stays open, and what the queued EDT work touches is the panel.
   */
  @Volatile
  private var disposed = false

  init {
    isOpaque = true
    background = PinboardColors.surface
    border = JBUI.Borders.compound(
      JBUI.Borders.customLineTop(PinboardColors.border),
      JBUI.Borders.empty(4, 10),
    )

    val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
      isOpaque = false
      add(dot)
      add(summary)
    }
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
      isOpaque = false
      add(toggle)
    }
    add(
      JPanel(BorderLayout()).apply {
        isOpaque = false
        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
      },
      BorderLayout.NORTH,
    )
    add(logScroll, BorderLayout.CENTER)

    toggle.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = toggleLog()
    })

    // Replays the backlog and subscribes as one step, so a line recorded while this panel is being
    // built cannot fall between the two.
    activity.subscribe(parent) { line -> onEdt { appendLine(line) } }

    ticker.isRepeats = true
    ticker.start()
    Disposer.register(parent) {
      disposed = true
      ticker.stop()
    }

    refresh()
  }

  /** The view the toolbar chip should show. Derived here so both read exactly one source. */
  fun currentView(): ConnectionView {
    // One read of one field: name and timestamp come from the same call or from neither.
    val call = activity.lastCall
    return connectionView(
      toolsetRegistered = ToolsetHealth.feedbackToolsetRegistered(),
      lastToolCallAt = call?.at,
      lastToolName = call?.name,
    )
  }

  /** Not gated on visibility: the one call from `init` has to paint the panel before it is shown. */
  fun refresh() {
    if (disposed) return
    val view = currentView()
    dot.foreground = view.tone.color
    summary.text = view.detail
    onViewChanged(view)
  }

  private fun toggleLog() {
    logScroll.isVisible = !logScroll.isVisible
    toggle.text = if (logScroll.isVisible) EXPANDED_LABEL else COLLAPSED_LABEL
    revalidate()
    repaint()
  }

  private fun appendLine(line: String) {
    logArea.append(line + "\n")
    logArea.caretPosition = logArea.document.length
  }

  private fun onEdt(action: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) {
      if (!disposed) action()
    } else {
      app.invokeLater({ if (!disposed) action() }, ModalityState.any())
    }
  }

  private companion object {
    const val REFRESH_MS = 2_000
    const val COLLAPSED_LABEL = "▸ Log"
    const val EXPANDED_LABEL = "▾ Log"
  }
}
