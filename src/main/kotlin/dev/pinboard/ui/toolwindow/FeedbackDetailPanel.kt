package dev.pinboard.ui.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SwingHelper
import com.intellij.util.ui.UIUtil
import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.ui.dialog.CodePreviewPanel
import dev.pinboard.ui.theme.PinboardFonts
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Right-hand detail for the selected feedback: where it points, whether the code moved, what the
 * user asked for, and the conversation with the agent.
 *
 * Rebuilt from scratch on every selection change. The previous code preview owns an editor, so it
 * is disposed through a per-selection [Disposable] before the next one is created.
 */
class FeedbackDetailPanel(
  private val project: Project,
  parentDisposable: Disposable,
) : JPanel(BorderLayout()), Disposable {

  private var previewDisposable: Disposable? = null

  init {
    Disposer.register(parentDisposable, this)
    showEmpty()
  }

  /** Renders [node], or the empty state when nothing is selected. */
  fun show(node: FeedbackItemNode?) {
    disposePreview()
    removeAll()
    if (node == null) showEmpty() else add(JBScrollPane(buildContent(node)), BorderLayout.CENTER)
    revalidate()
    repaint()
  }

  private fun showEmpty() {
    val label = JBLabel("Select a feedback item", UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER)
    label.border = JBUI.Borders.empty(12)
    add(label, BorderLayout.NORTH)
  }

  private fun buildContent(node: FeedbackItemNode): JComponent {
    val feedback = node.feedback
    val content = JPanel(VerticalLayout(JBUI.scale(8)))
    content.border = JBUI.Borders.empty(8)

    content.add(header(feedback))
    staleBanner(node)?.let { content.add(it) }
    content.add(sectionLabel("Feedback"))
    content.add(htmlBlock(feedback.note))

    codePreview(feedback)?.let {
      content.add(sectionLabel("Code at capture time"))
      content.add(it)
    }

    if (feedback.thread.isNotEmpty()) {
      content.add(sectionLabel("Conversation"))
      feedback.thread.forEach { content.add(htmlBlock(messageHtml(it), escaped = true)) }
    }
    return content
  }

  private fun header(feedback: Feedback): JComponent {
    val location = when {
      feedback.scope == Scope.PROJECT -> "Whole project"
      feedback.filePath == null -> "Unknown location"
      feedback.startLine == null -> feedback.filePath
      feedback.endLine != null && feedback.endLine != feedback.startLine ->
        "${feedback.filePath}:${feedback.startLine}-${feedback.endLine}"
      else -> "${feedback.filePath}:${feedback.startLine}"
    }
    val status = StatusAppearance.label(feedback.status)
    val created = DateFormatUtil.formatPrettyDateTime(feedback.createdAt)
    return htmlBlock(
      "<b>${escape(location)}</b><br/>${escape(status)} &middot; ${escape(created)}",
      escaped = true,
    )
  }

  /**
   * Warns that current line numbers cannot be trusted. The snapshot below stays authoritative -
   * this is exactly the case `symbolPath` exists to recover from.
   */
  private fun staleBanner(node: FeedbackItemNode): JComponent? {
    val message = when {
      node.fileMissing -> "The file no longer exists. The snapshot below is what was pinned."
      node.stale -> "Code changed since this was pinned. Line numbers may be wrong; trust the snapshot below."
      else -> return null
    }
    val label = JBLabel(message)
    label.foreground = UIUtil.getErrorForeground()
    label.border = JBUI.Borders.empty(4, 0)
    return label
  }

  private fun codePreview(feedback: Feedback): JComponent? {
    if (feedback.codeSnapshot == null) return null
    val disposable = Disposer.newDisposable("pinboard-detail-preview")
    Disposer.register(this, disposable)
    previewDisposable = disposable

    val panel = CodePreviewPanel(project, feedback.toSnapshot())
    panel.bindToDisposable(disposable)
    return panel
  }

  private fun messageHtml(message: dev.pinboard.model.Message): String {
    val who = if (message.author == Author.HUMAN) "You" else "Agent"
    val time = DateFormatUtil.formatPrettyDateTime(message.createdAt)
    val body = escape(message.body).replace("\n", "<br/>")
    return "<b>${escape(who)}</b> <font color=\"gray\">${escape(time)}</font><br/>$body"
  }

  /**
   * Wrapping text block. User and agent text is escaped here - the store keeps it verbatim and the
   * display layer is where it becomes safe to render.
   *
   * Sized like the note in the queue card, so the same text does not change size when the user
   * clicks it open.
   */
  private fun htmlBlock(text: String, escaped: Boolean = false): JComponent {
    val body = if (escaped) text else escape(text).replace("\n", "<br/>")
    val viewer = SwingHelper.createHtmlViewer(true, PinboardFonts.note(), null, null)
    viewer.text = "<html><body>$body</body></html>"
    viewer.border = JBUI.Borders.empty()
    return viewer
  }

  private fun sectionLabel(text: String): JComponent {
    val label = JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER)
    label.border = JBUI.Borders.emptyTop(4)
    return label
  }

  private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

  private fun disposePreview() {
    previewDisposable?.let { Disposer.dispose(it) }
    previewDisposable = null
  }

  override fun dispose() {
    disposePreview()
  }
}

/** Adapts a stored item back to the shape [CodePreviewPanel] renders. */
private fun Feedback.toSnapshot(): SelectionSnapshot = SelectionSnapshot(
  filePath = filePath,
  language = language,
  startLine = startLine,
  endLine = endLine,
  codeSnapshot = codeSnapshot,
  contentSha256 = contentSha256,
  truncated = truncated,
  symbolPath = symbolPath,
  vcsRevision = vcsRevision,
)
