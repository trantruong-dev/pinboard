package dev.pinboard.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.util.ProjectFiles
import dev.pinboard.util.Sha256

/**
 * Detects whether a feedback item's code has changed since capture.
 *
 * Reads the current file (via the VFS), extracts the recorded line range, hashes it, and compares
 * against the stored [Feedback.contentSha256]. A mismatch (or a missing file) means the agent
 * should not trust current line numbers - it must use [Feedback.codeSnapshot] as the source of
 * truth and [Feedback.symbolPath] to relocate.
 *
 * Runs inside a [ReadAction]. Never throws; returns [StaleResult].
 */
object StaleDetector {

  fun check(project: Project, feedback: Feedback): StaleResult {
    if (feedback.scope != Scope.SELECTION) return StaleResult(stale = false, fileMissing = false)
    val filePath = feedback.filePath ?: return StaleResult(false, false)
    val startLine = feedback.startLine ?: return StaleResult(false, false)
    val endLine = feedback.endLine ?: return StaleResult(false, false)
    val expectedSha = feedback.contentSha256 ?: return StaleResult(false, false)

    return ReadAction.compute<StaleResult, Throwable> {
      try {
        val currentText = readCurrentRange(project, filePath, startLine, endLine)
          ?: return@compute StaleResult(stale = true, fileMissing = true)
        val currentSha = Sha256.hex(Sha256.normalizeForComparison(currentText))
        StaleResult(stale = currentSha != expectedSha, fileMissing = false)
      } catch (_: Throwable) {
        StaleResult(stale = true, fileMissing = false)
      }
    }
  }

  private fun readCurrentRange(project: Project, filePath: String, startLine: Int, endLine: Int): String? {
    val vf = ProjectFiles.resolve(project, filePath) ?: return null
    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
      ?: return null
    if (startLine < 1 || endLine < startLine) return null
    val text = document.text
    val lines = text.split("\n")
    if (lines.isEmpty()) return null
    val from = (startLine - 1).coerceIn(0, lines.size - 1)
    val to = (endLine - 1).coerceIn(from, lines.size - 1)
    return lines.subList(from, to + 1).joinToString("\n")
  }
}

data class StaleResult(
  val stale: Boolean,
  val fileMissing: Boolean,
)