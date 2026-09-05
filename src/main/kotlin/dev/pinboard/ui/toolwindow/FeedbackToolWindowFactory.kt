package dev.pinboard.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates the "Pinboard" ToolWindow.
 *
 * [DumbAware] because the queue is plain stored data - it stays usable while indexes are building,
 * which is exactly when a developer is likely to be reviewing what an agent just wrote.
 */
class FeedbackToolWindowFactory : ToolWindowFactory, DumbAware {

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val content = ContentFactory.getInstance().createContent(null, "Feedback", false)
    val panel = FeedbackListPanel(project, content, toolWindow.disposable, toolWindow)
    content.component = panel
    content.isCloseable = false
    toolWindow.contentManager.addContent(content)
  }
}
