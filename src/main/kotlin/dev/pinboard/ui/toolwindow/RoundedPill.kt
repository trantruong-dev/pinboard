package dev.pinboard.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * A small rounded label: tinted background, a status dot, then the text. Currently the connection
 * chip's body.
 *
 * Painted rather than assembled from a bordered label so the corner radius always matches the height
 * and the whole thing scales with the IDE's font size.
 *
 * Both colours are [JBColor]s and are resolved inside [paintComponent] rather than stored resolved,
 * so a pill that is never rebuilt still repaints in the right theme after the user switches.
 */
class RoundedPill(
  private val text: String,
  private val foregroundColor: JBColor,
  private val backgroundColor: JBColor,
) : JComponent() {

  init {
    font = JBUI.Fonts.smallFont()
    isOpaque = false
  }

  private val horizontalPadding get() = JBUI.scale(8)
  private val verticalPadding get() = JBUI.scale(3)
  private val dotSize get() = JBUI.scale(6)
  private val dotGap get() = JBUI.scale(5)

  override fun getPreferredSize(): Dimension {
    val metrics = getFontMetrics(font)
    return Dimension(
      horizontalPadding * 2 + dotSize + dotGap + metrics.stringWidth(text),
      verticalPadding * 2 + metrics.height,
    )
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      // Arc equal to the height gives a true capsule at any font scale.
      val arc = height.toFloat()
      g2.color = backgroundColor
      g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))

      g2.color = foregroundColor
      g2.fillOval(horizontalPadding, (height - dotSize) / 2, dotSize, dotSize)

      val metrics = g2.fontMetrics
      g2.font = font
      g2.drawString(
        text,
        horizontalPadding + dotSize + dotGap,
        (height - metrics.height) / 2 + metrics.ascent,
      )
    } finally {
      g2.dispose()
    }
  }
}
