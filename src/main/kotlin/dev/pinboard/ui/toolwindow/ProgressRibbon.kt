package dev.pinboard.ui.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pinboard.model.Status
import dev.pinboard.ui.theme.PinboardColors
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * How much of the queue is done, as a thin proportional bar plus a legend.
 *
 * A count per group is already in the headers; this answers the different question of how the review
 * as a whole is going, which is otherwise arithmetic the user has to do in their head.
 */
class ProgressRibbon : JPanel(BorderLayout(0, JBUI.scale(4))) {

  private val bar = Bar()
  private val labels = SEGMENTS.associateWith { segment ->
    JBLabel().apply {
      font = JBFont.small()
      foreground = segment.color
    }
  }

  init {
    isOpaque = false
    border = JBUI.Borders.empty(6, 12, 6, 12)
    add(bar, BorderLayout.NORTH)

    val legend = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      isOpaque = false
    }
    SEGMENTS.forEachIndexed { index, segment ->
      if (index > 0) legend.add(Box.createHorizontalStrut(JBUI.scale(10)))
      legend.add(labels.getValue(segment))
    }
    legend.add(Box.createHorizontalGlue())
    add(legend, BorderLayout.CENTER)
  }

  /** Recomputes from the whole queue. Hides itself when there is nothing to show progress on. */
  fun update(items: List<dev.pinboard.model.Feedback>) {
    val counts = segmentCounts(items)
    isVisible = items.isNotEmpty()
    SEGMENTS.forEachIndexed { index, segment ->
      labels.getValue(segment).text = "● ${counts[index]} ${segment.label}"
    }
    bar.set(counts)
    revalidate()
    repaint()
  }

  /** Segment order is left to right: what is done, then what is in flight, then what is owed. */
  private enum class Segment(val label: String, val statuses: Set<Status>) {
    DONE("Done", setOf(Status.RESOLVED, Status.DISMISSED)),
    ACKNOWLEDGED("Acknowledged", setOf(Status.ACKNOWLEDGED)),
    PENDING("Pending", setOf(Status.PENDING)),
    ;

    val color: Color get() = StatusAppearance.color(statuses.first())
  }

  /**
   * The bar. Values ease toward their target over a few frames so a segment visibly grows when an
   * agent resolves something, rather than the whole bar snapping and being missed.
   */
  private class Bar : JComponent() {
    private var current = FloatArray(SEGMENTS.size)
    private var target = IntArray(SEGMENTS.size)
    private var initialised = false
    private val animator = javax.swing.Timer(FRAME_MS) { tick() }

    fun set(values: List<Int>) {
      target = values.toIntArray()
      if (!initialised) {
        // The first paint is the queue as it already is, not an animation from zero.
        current = FloatArray(values.size) { values[it].toFloat() }
        initialised = true
        repaint()
      } else if (!animator.isRunning) {
        animator.start()
      }
    }

    private fun tick() {
      var settled = true
      for (i in current.indices) {
        current[i] += (target[i] - current[i]) * EASING
        if (kotlin.math.abs(target[i] - current[i]) >= 0.5f) settled = false
      }
      if (settled) {
        current = FloatArray(target.size) { target[it].toFloat() }
        animator.stop()
      }
      repaint()
    }

    /** Stops the timer when the panel goes away; a running Swing Timer keeps its target alive. */
    override fun removeNotify() {
      animator.stop()
      super.removeNotify()
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(120), JBUI.scale(5))

    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, JBUI.scale(5))

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val h = height.toFloat()
        val w = width.toFloat()
        g2.color = PinboardColors.soft(PinboardColors.textMuted)
        g2.fill(RoundRectangle2D.Float(0f, 0f, w, h, h, h))

        val total = current.sum()
        if (total <= 0f) return
        var x = 0f
        SEGMENTS.forEachIndexed { index, segment ->
          val value = current[index]
          if (value <= 0f) return@forEachIndexed
          val segmentWidth = w * value / total
          g2.color = segment.color
          g2.fill(RoundRectangle2D.Float(x, 0f, segmentWidth, h, h, h))
          x += segmentWidth
        }
      } finally {
        g2.dispose()
      }
    }
  }

  companion object {
    private val SEGMENTS = Segment.entries
    private const val FRAME_MS = 16
    private const val EASING = 0.25f

    /**
     * How many items fall in each bar segment, left to right.
     *
     * Split out from painting so the arithmetic the bar rests on can be checked without a screen -
     * a wrong count here is a bar that quietly lies about how much work is left.
     */
    fun segmentCounts(items: List<dev.pinboard.model.Feedback>): List<Int> =
      SEGMENTS.map { segment -> items.count { it.status in segment.statuses } }

    /** The segment legend, left to right. */
    fun segmentLabels(): List<String> = SEGMENTS.map { it.label }
  }
}
