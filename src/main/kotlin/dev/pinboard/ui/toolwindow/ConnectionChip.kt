package dev.pinboard.ui.toolwindow

import dev.pinboard.ui.theme.PinboardColors
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The at-a-glance answer to "is my agent actually talking to this?", at the right end of the tool
 * window toolbar.
 *
 * A thin wrapper over [RoundedPill] rather than a painting component of its own: the chip's whole
 * job is to swap in a differently coloured pill as [ConnectionView] changes, and a pill's colours
 * are fixed at construction.
 */
class ConnectionChip : JPanel(BorderLayout()) {

  private var view: ConnectionView? = null

  init {
    isOpaque = false
  }

  fun update(view: ConnectionView) {
    // Same state, same pill. Rebuilding on every 2s tick would fight the user's mouse hover, and
    // the pill needs no rebuild to follow a theme switch - its JBColors resolve when it paints.
    if (this.view == view) return
    this.view = view

    removeAll()
    add(
      RoundedPill(
        text = view.chipLabel,
        foregroundColor = view.tone.color,
        backgroundColor = PinboardColors.soft(view.tone.color),
      ),
      BorderLayout.CENTER,
    )
    toolTipText = view.detail
    revalidate()
    repaint()
  }
}
