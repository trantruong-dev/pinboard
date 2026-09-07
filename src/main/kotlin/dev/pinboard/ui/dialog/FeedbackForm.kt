package dev.pinboard.ui.dialog

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.ui.theme.PinboardColors
import dev.pinboard.ui.theme.PinboardFonts
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * The body of both capture paths: what is being pinned, and the note about it.
 *
 * There is one class rather than one per path because the balloon and the dialog must not drift.
 * They differ only in the frame around this - a popup anchored at the caret, or a modal window -
 * so a note typed in one has to mean exactly what the same note typed in the other means.
 */
class FeedbackForm(
  private val snapshot: SelectionSnapshot?,
  showLocation: Boolean = true,
  initialNote: String = "",
) : JPanel() {

  val noteArea = JBTextArea(NOTE_ROWS, NOTE_COLUMNS).apply {
    lineWrap = true
    wrapStyleWord = true
    emptyText.text = "What should the agent do here?"
    // Seeding through the constructor rather than letting callers reach into noteArea keeps the
    // two capture paths from drifting: whatever prefill means, it means the same in both.
    text = initialNote
    // Reopening a note to edit it usually means adding to the end, not replacing it. Measured off
    // the document rather than the string: this runs in a constructor, where a document that
    // normalised the text would turn a mismatch into a throw.
    caretPosition = document.length
  }

  init {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false

    if (showLocation) {
      add(
        JBLabel(location(snapshot)).apply {
          font = JBFont.small()
          foreground = PinboardColors.textMuted
          alignmentX = LEFT_ALIGNMENT
        },
      )
      add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
    }

    snippet()?.let {
      add(it)
      add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
    }

    add(
      JBScrollPane(noteArea).apply {
        alignmentX = LEFT_ALIGNMENT
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(JBUI.scale(NOTE_WIDTH), JBUI.scale(NOTE_HEIGHT))
      },
    )
  }

  /** The typed note, trimmed. Blank means the user has not written anything worth storing. */
  fun note(): String = noteArea.text.trim()

  fun hasNote(): Boolean = note().isNotEmpty()

  /**
   * The pinned code, quoted.
   *
   * Read-only and non-focusable: tabbing must land in the note, which is the only thing here the
   * user is meant to fill in. Null when there is no code - whole-file and project scope.
   */
  private fun snippet(): JComponent? {
    val code = snapshot?.codeSnapshot?.takeIf { it.isNotBlank() } ?: return null
    val area = JBTextArea(trimToFit(code)).apply {
      isEditable = false
      isFocusable = false
      isOpaque = false
      font = PinboardFonts.editor()
      border = JBUI.Borders.empty(4, 12, 4, 6)
    }
    return QuoteFrame().apply {
      alignmentX = LEFT_ALIGNMENT
      add(
        JBScrollPane(area).apply {
          border = JBUI.Borders.empty()
          isOpaque = false
          viewport.isOpaque = false
          horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        },
        BorderLayout.CENTER,
      )
      preferredSize = Dimension(JBUI.scale(NOTE_WIDTH), JBUI.scale(SNIPPET_HEIGHT))
      maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(SNIPPET_HEIGHT))
    }
  }

  /** The quote frame: a soft ground with the status colour running down its left edge. */
  private class QuoteFrame : JPanel(BorderLayout()) {
    init {
      isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val radius = JBUI.scale(6).toFloat()
        g2.color = PinboardColors.soft(PinboardColors.textMuted)
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), radius, radius))
        // A new item is always PENDING, so the bar states what this capture will become.
        g2.color = PinboardColors.statusPending
        val barWidth = JBUI.scale(3).toFloat()
        g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, height.toFloat(), barWidth, barWidth))
      } finally {
        g2.dispose()
      }
    }
  }

  companion object {
    private const val NOTE_ROWS = 4
    private const val NOTE_COLUMNS = 48
    private const val NOTE_WIDTH = 420
    private const val NOTE_HEIGHT = 96
    private const val SNIPPET_HEIGHT = 108

    /** Long selections are pinned whole; only the preview is capped. */
    const val SNIPPET_PREVIEW_LINES = 12

    /**
     * Where the note points, for the context strip.
     *
     * The full relative path, not just the file name: two files can share a name, and this is the
     * moment the user decides whether they picked the right one.
     */
    fun location(snapshot: SelectionSnapshot?): String {
      val path = snapshot?.filePath ?: return "Whole project"
      val start = snapshot.startLine ?: return path
      val end = snapshot.endLine
      return if (end != null && end != start) "$path : $start-$end" else "$path : $start"
    }

    /**
     * Caps the preview so a large selection cannot push the note box off a balloon, which has no
     * scroll of its own and would simply run past the bottom of the screen.
     */
    fun trimToFit(code: String): String {
      val lines = code.lines()
      if (lines.size <= SNIPPET_PREVIEW_LINES) return code.trimEnd()
      val hidden = lines.size - SNIPPET_PREVIEW_LINES
      return (lines.take(SNIPPET_PREVIEW_LINES) + "… $hidden more line${if (hidden == 1) "" else "s"}")
        .joinToString(System.lineSeparator())
    }
  }
}
