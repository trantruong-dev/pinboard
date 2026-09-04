package dev.pinboard.ui.toolwindow

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pinboard.model.Scope
import dev.pinboard.ui.theme.PinboardColors
import dev.pinboard.util.FilePaths
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders the queue: a status header row, or a feedback card.
 *
 * The card exists because the old single-line tree row was unreadable in a docked panel - a long
 * note was simply clipped. Here the note is laid out as HTML at the list's current width, so it
 * wraps instead of disappearing when the user narrows the tool window.
 */
class FeedbackCardRenderer : ListCellRenderer<FeedbackRow> {

  override fun getListCellRendererComponent(
    list: JList<out FeedbackRow>,
    value: FeedbackRow,
    index: Int,
    isSelected: Boolean,
    cellHasFocus: Boolean,
  ): Component = when (value) {
    is FeedbackRow.StatusHeader -> header(value, isSelected)
    is FeedbackRow.Item -> card(value.node, isSelected, list.width)
  }

  private fun header(row: FeedbackRow.StatusHeader, selected: Boolean): JComponent {
    val panel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
      isOpaque = true
      background = rowBackground(selected)
      border = JBUI.Borders.empty(6, 8, 4, 10)
    }
    val line = horizontal()
    line.add(
      JBLabel(if (row.collapsed) "▸" else "▾").apply {
        foreground = PinboardColors.textMuted
        border = JBUI.Borders.emptyRight(4)
      },
    )
    line.add(
      JBLabel(StatusAppearance.label(row.status)).apply {
        font = JBFont.regular().asBold()
        foreground = PinboardColors.textPrimary
      },
    )
    line.add(Box.createHorizontalStrut(JBUI.scale(6)))
    line.add(
      JBLabel(row.total.toString()).apply {
        font = JBFont.small()
        foreground = PinboardColors.textMuted
      },
    )
    line.add(Box.createHorizontalGlue())
    panel.add(line, BorderLayout.CENTER)
    return panel
  }

  private fun card(node: FeedbackItemNode, selected: Boolean, listWidth: Int): JComponent {
    val feedback = node.feedback
    val accent = StatusAppearance.color(feedback.status)
    val panel = AccentedCard(accent).apply {
      isOpaque = true
      background = rowBackground(selected)
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      border = JBUI.Borders.empty(7, 16, 7, 10)
    }

    val top = horizontal()
    location(node)?.let {
      top.add(
        JBLabel(it).apply {
          font = JBFont.small()
          foreground = PinboardColors.textMuted
        },
      )
    }
    StatusAppearance.warning(node)?.let {
      top.add(Box.createHorizontalStrut(JBUI.scale(6)))
      top.add(
        JBLabel(it).apply {
          font = JBFont.small()
          foreground = PinboardColors.statusError
        },
      )
    }
    top.add(Box.createHorizontalGlue())
    if (top.componentCount > 1) {
      panel.add(top)
      panel.add(Box.createVerticalStrut(JBUI.scale(4)))
    }

    // The note, wrapped to whatever width the list has right now. This is the whole point of the
    // card: the previous single-line row clipped anything that did not fit and lost it silently.
    panel.add(
      JBLabel(wrapped(feedback.note, bodyWidth(listWidth))).apply {
        font = JBFont.regular()
        foreground = PinboardColors.textPrimary
        alignmentX = Component.LEFT_ALIGNMENT
      },
    )

    feedback.codeSnapshot?.firstMeaningfulLine()?.let { snippet ->
      panel.add(Box.createVerticalStrut(JBUI.scale(4)))
      panel.add(
        JBLabel(escaped(truncate(snippet, SNIPPET_CHARS))).apply {
          // Editor font: this is code, and setting it apart from the note is what makes a glance
          // enough to tell which is which.
          font = editorFont()
          foreground = PinboardColors.textMuted
          alignmentX = Component.LEFT_ALIGNMENT
        },
      )
    }

    panel.add(Box.createVerticalStrut(JBUI.scale(4)))
    panel.add(
      JBLabel(relativeTime(feedback.createdAt)).apply {
        font = JBFont.small()
        foreground = PinboardColors.textMuted
        alignmentX = Component.LEFT_ALIGNMENT
      },
    )

    // Rows get clipped no matter how well they wrap, so the full note and path stay reachable.
    panel.toolTipText = escaped(
      buildString {
        append(feedback.note.trim())
        feedback.filePath?.let { append(System.lineSeparator()).append(it) }
      },
    )
    return panel
  }

  private fun horizontal() = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
  }

  private fun rowBackground(selected: Boolean): Color =
    if (selected) PinboardColors.selectionBg else PinboardColors.surface

  private fun editorFont(): Font {
    val scheme = EditorColorsManager.getInstance().globalScheme
    return Font(scheme.editorFontName, Font.PLAIN, JBUI.scale(11))
  }

  /** `Foo.kt:40-52` for selections, `Foo.kt` for whole files, nothing for project scope. */
  private fun location(node: FeedbackItemNode): String? {
    val feedback = node.feedback
    if (feedback.scope == Scope.PROJECT) return null
    val path = feedback.filePath ?: return null
    val fileName = FilePaths.fileName(path)
    val start = feedback.startLine ?: return fileName
    val end = feedback.endLine
    return if (end != null && end != start) "$fileName:$start-$end" else "$fileName:$start"
  }

  private fun bodyWidth(listWidth: Int): Int =
    (listWidth - JBUI.scale(HORIZONTAL_CHROME)).coerceAtLeast(JBUI.scale(MIN_BODY_WIDTH))

  private fun wrapped(note: String, widthPx: Int): String {
    val collapsed = note.trim().replace(WHITESPACE, " ")
    val clipped = truncate(collapsed, NOTE_CHARS)
    return "<html><body style='width:${widthPx}px'>${escaped(clipped)}</body></html>"
  }

  /**
   * Swing renders any label or tooltip whose text starts with `<html>` as markup, so pinning the
   * top of an HTML file would render the user's own code instead of showing it.
   */
  private fun escaped(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

  /** Skips blank leading lines so the preview is code rather than indentation. */
  private fun String.firstMeaningfulLine(): String? =
    lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() }

  /** Paints the rounded status bar down the card's left edge. */
  private class AccentedCard(private val accent: Color) : JPanel() {
    override fun paintComponent(g: Graphics) {
      super.paintComponent(g)
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = accent
        val x = JBUI.scale(7).toFloat()
        val w = JBUI.scale(3).toFloat()
        val inset = JBUI.scale(5).toFloat()
        g2.fill(RoundRectangle2D.Float(x, inset, w, height - inset * 2, w, w))
      } finally {
        g2.dispose()
      }
    }
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")

    /** Padding plus the accent bar, i.e. everything the note cannot use. */
    const val HORIZONTAL_CHROME = 40
    const val MIN_BODY_WIDTH = 100
    const val NOTE_CHARS = 220
    const val SNIPPET_CHARS = 60
  }
}
