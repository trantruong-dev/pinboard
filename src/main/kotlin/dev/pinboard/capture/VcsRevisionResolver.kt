package dev.pinboard.capture

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager

/**
 * Resolves the git HEAD revision (sha) for a file at capture time.
 * Uses git4idea's repository manager (bundled with every JetBrains IDE). Not a git repo, or
 * git4idea not available, or no matching repository -> null. Runs inside a [ReadAction].
 */
object VcsRevisionResolver {

  fun resolve(project: Project, file: VirtualFile): String? {
    return ReadAction.compute<String?, Throwable> {
      try {
        val manager = GitRepositoryManager.getInstance(project)
        val repository = manager.getRepositoryForFile(file) ?: return@compute null
        repository.info.currentRevision
      } catch (_: Throwable) {
        null
      }
    }
  }
}