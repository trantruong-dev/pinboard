package dev.pinboard.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.pinboard.capture.SnapshotCaptureService
import dev.pinboard.model.Scope

/**
 * Project tree / editor tab action: capture the whole file (scope FILE) as a feedback item.
 * Disabled for directories, binary files, and files outside the project root.
 */
class CaptureFileAction : AnAction() {

  override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = e.project
    val enabled = file != null && project != null && isCapturableFile(file, project)
    e.presentation.isEnabledAndVisible = enabled
    if (!enabled && file != null && project != null && !isCapturableFile(file, project)) {
      e.presentation.description = "Cannot capture feedback from this file (binary, scratch, or outside project)"
    }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    if (!isCapturableFile(file, project)) return
    CaptureActionSupport.capture(project, Scope.FILE) {
      SnapshotCaptureService.getInstance(project).captureFile(file)
    }
  }

  private fun isCapturableFile(file: VirtualFile, project: Project): Boolean {
    return !file.isDirectory &&
      !file.fileType.isBinary &&
      isInsideProjectRoot(file, project)
  }

  private fun isInsideProjectRoot(file: VirtualFile, project: Project): Boolean {
    // A file belongs to the project if it sits inside a content root. Scratch files and files
    // opened from outside the project have no content root (or belong to a scratch root type).
    val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
    return fileIndex.getContentRootForFile(file) != null
  }
}