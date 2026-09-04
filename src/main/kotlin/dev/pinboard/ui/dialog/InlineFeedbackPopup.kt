package dev.pinboard.ui.dialog

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.ui.theme.PinboardColors
import dev.pinboard.util.FilePaths
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Captures a note in a balloon anchored at the caret, instead of a modal window.
 *
 * The product is "pin it while you are reading, keep reading, hand the batch over later". A modal
 * dialog works against exactly that: it takes the middle of the screen, hides the code the note is
 * about, and breaks the reading. The balloon leaves the editor where it is.
 */
object InlineFeedbackPopup {

  /**
   * Shows the balloon for [snapshot] over [editor]. [onSubmit] receives the trimmed note and is
   * never called with a blank one.
   */
  fun show(editor: Editor, snapshot: SelectionSnapshot?, onSubmit: (String) -> Unit) {
    val form = FeedbackForm(snapshot, showLocation = false)
    val submit = JButton("Add")
    val content = JPanel(BorderLayout()).apply {
      border = JBUI.Borders.empty(8, 10)
      add(header(snapshot), BorderLayout.NORTH)
      add(form, BorderLayout.CENTER)
      add(footer(submit), BorderLayout.SOUTH)
    }

    val popup = JBPopupFactory.getInstance()
      .createComponentPopupBuilder(content, form.noteArea)
      .setRequestFocus(true)
      .setFocusable(true)
      .setMovable(true)
      .setResizable(true)
      .setCancelOnWindowDeactivation(false)
      // A half-typed note is the user's own words and is not recoverable. Clicking into the editor
      // to re-read the code being pinned is a normal thing to do while writing one, and must not
      // throw the note away - Esc and the editor's own dismissal are the ways out.
      .setCancelOnClickOutside(false)
      .createPopup()

    val commit = {
      if (form.hasNote()) {
        val note = form.note()
        popup.closeOk(null)
        onSubmit(note)
      }
    }
    submit.addActionListener { commit() }
    bindSubmitShortcut(form.noteArea, commit)
    wireEnablement(form, submit)

    popup.showInBestPositionFor(editor)
  }

  /** `Pin  ·  Foo.kt : 40-52  ·  Pending` - what is about to be created, in one line. */
  private fun header(snapshot: SelectionSnapshot?): JComponent {
    val line = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.X_AXIS)
      isOpaque = false
      border = JBUI.Borders.emptyBottom(6)
    }
    line.add(
      JBLabel("Pin").apply {
        font = JBFont.regular().asBold()
        foreground = PinboardColors.textPrimary
      },
    )
    line.add(separator())
    line.add(
      JBLabel(shortLocation(snapshot)).apply {
        font = JBFont.small()
        foreground = PinboardColors.textMuted
      },
    )
    line.add(separator())
    line.add(
      JBLabel("Pending").apply {
        font = JBFont.small()
        foreground = PinboardColors.statusPending
      },
    )
    line.add(Box.createHorizontalGlue())
    return line
  }

  private fun separator() = JBLabel("·").apply {
    font = JBFont.small()
    foreground = PinboardColors.textMuted
    border = JBUI.Borders.empty(0, 6)
  }

  private fun footer(submit: JButton): JComponent {
    val hint = JBLabel("Esc to cancel · ${submitShortcutHint()} to add").apply {
      font = JBFont.small()
      foreground = PinboardColors.textMuted
    }
    val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
      isOpaque = false
      add(hint)
    }
    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
      isOpaque = false
      add(submit)
    }
    return JPanel(BorderLayout()).apply {
      isOpaque = false
      border = JBUI.Borders.emptyTop(8)
      add(left, BorderLayout.WEST)
      add(right, BorderLayout.EAST)
    }
  }

  /**
   * The note area is multi-line, so plain Enter has to keep inserting a newline. Submitting is
   * bound to the platform's usual "commit this text area" chord instead.
   */
  private fun bindSubmitShortcut(noteArea: JComponent, commit: () -> Unit) {
    val action = object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) = commit()
    }
    for (stroke in submitShortcuts()) {
      noteArea.inputMap.put(stroke, SUBMIT_KEY)
    }
    noteArea.actionMap.put(SUBMIT_KEY, action)
  }

  /** Greys out Add until there is something to add, so the button never silently does nothing. */
  private fun wireEnablement(form: FeedbackForm, submit: JButton) {
    submit.isEnabled = form.hasNote()
    form.noteArea.document.addDocumentListener(
      object : javax.swing.event.DocumentListener {
        private fun sync() {
          submit.isEnabled = form.hasNote()
        }

        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = sync()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = sync()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = sync()
      },
    )
  }

  /** Just the file name and range: the balloon sits on the code, so the full path is noise. */
  fun shortLocation(snapshot: SelectionSnapshot?): String {
    val path = snapshot?.filePath ?: return "whole project"
    val name = FilePaths.fileName(path)
    val start = snapshot.startLine ?: return name
    val end = snapshot.endLine
    return if (end != null && end != start) "$name : $start-$end" else "$name : $start"
  }

  /** Ctrl+Enter everywhere, plus Cmd+Enter, which is what a Mac user reaches for. */
  fun submitShortcuts(): List<KeyStroke> = listOf(
    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK),
    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.META_DOWN_MASK),
  )

  private fun submitShortcutHint(): String =
    if (com.intellij.openapi.util.SystemInfo.isMac) "⌘↵" else "Ctrl+Enter"

  private const val SUBMIT_KEY = "pinboard.submit"
}
