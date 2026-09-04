package dev.pinboard.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Resolves the project-relative paths stored on feedback items back to VFS files.
 *
 * Feedback stores [dev.pinboard.model.Feedback.filePath] relative to the project root so the
 * queue survives moving machines, worktrees and clone paths. Every consumer that needs the real
 * file (stale detection, ToolWindow navigation) goes through here so the resolution rules stay in
 * one place.
 */
object ProjectFiles {

  /**
   * Returns the file for [relativePath], or null when the project or the file is gone.
   *
   * The separator is normalised here as well as at capture time: `findFileByRelativePath` only
   * understands forward slashes, and items captured before that normalisation existed are still
   * sitting in users' queues with Windows separators.
   */
  fun resolve(project: Project, relativePath: String): VirtualFile? {
    val baseDir = projectRoot(project) ?: return null
    return baseDir.findFileByRelativePath(FilePaths.canonical(relativePath))
  }

  /**
   * Project root as a VFS directory. `Project.getBaseDir()` is deprecated, so the root is derived
   * from the absolute base path instead. Backslashes are normalised because the store always keeps
   * forward slashes, including on Windows.
   */
  fun projectRoot(project: Project): VirtualFile? {
    val basePath = project.basePath ?: return null
    return LocalFileSystem.getInstance().findFileByPath(FilePaths.canonical(basePath))
  }
}
