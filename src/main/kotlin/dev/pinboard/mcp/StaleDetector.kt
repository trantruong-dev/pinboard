package dev.pinboard.mcp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import dev.pinboard.capture.AnchorRegistry
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
    val expectedSha = feedback.contentSha256 ?: return StaleResult(false, false)

    // A live anchor knows where the code moved to; the stored lines only know where it used to be.
    // Without this, inserting a line above a pin reports it stale even though its code is untouched.
    // No anchor - after a restart, or a file that was never opened - and this is the original path,
    // unchanged.
    val anchored = AnchorRegistry.getInstance(project).lineRange(feedback.id)
    val startLine = anchored?.first ?: feedback.startLine ?: return StaleResult(false, false)
    val endLine = anchored?.second ?: feedback.endLine ?: return StaleResult(false, false)

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
    if (document.lineCount == 0) return null
    // Read just the range. Copying and splitting the whole document to look at one or two lines
    // is fine once, but this runs per item per rebuild, and rebuilds now follow every edit that
    // moves a pin.
    val from = (startLine - 1).coerceIn(0, document.lineCount - 1)
    val to = (endLine - 1).coerceIn(from, document.lineCount - 1)
    return document.getText(
      TextRange(document.getLineStartOffset(from), document.getLineEndOffset(to)),
    )
  }
}

data class StaleResult(
  val stale: Boolean,
  val fileMissing: Boolean,
)