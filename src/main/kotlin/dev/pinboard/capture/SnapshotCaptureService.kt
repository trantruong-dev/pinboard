package dev.pinboard.capture

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import dev.pinboard.util.FilePaths
import dev.pinboard.util.Sha256

/**
 * Builds a [SelectionSnapshot] for the current editor selection or the whole file.
 * All reads (document, PSI, VCS) are wrapped in [ReadAction], so callers may run this off the EDT.
 */
@Service(Service.Level.PROJECT)
class SnapshotCaptureService(private val project: Project) {

  fun captureSelection(editor: Editor, file: VirtualFile): SelectionSnapshot? {
    return ReadAction.compute<SelectionSnapshot?, Throwable> {
      if (!editor.selectionModel.hasSelection()) return@compute null
      val document = editor.document
      val start = editor.selectionModel.selectionStart
      val end = editor.selectionModel.selectionEnd
      // endLine is 1-based inclusive. When the selection ends exactly at a line-start offset
      // (e.g. select-all on a doc ending with a line separator), getLineNumber(end) returns the
      // next line - clamp so endLine never exceeds the last line that actually contains text.
      var endLine = document.getLineNumber(end.coerceAtLeast(start + 1))
      if (end > start && end == document.getLineStartOffset(endLine)) {
        endLine = endLine - 1
      }
      val startLine = document.getLineNumber(start)

      // Widen the captured text to whole lines. The stored range is line-based and StaleDetector
      // re-derives the current text line by line, so a snapshot that began mid-line - selecting a
      // symbol or an expression, which is the common case - could never match it, and every such
      // item reported stale forever with no edit involved.
      val snapshotText = document.getText(
        TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine))
      )
      build(
        file = file,
        startLine = startLine + 1,
        endLine = endLine + 1,
        codeSnapshot = snapshotText,
        // Keep the caret's own offset for symbol resolution: it points at what the user actually
        // selected, which gives a better symbolPath than the start of the line.
        offset = start,
      )
    }
  }

  fun captureFile(file: VirtualFile): SelectionSnapshot? {
    return ReadAction.compute<SelectionSnapshot?, Throwable> {
      build(
        file = file,
        startLine = null,
        endLine = null,
        codeSnapshot = null,
        offset = 0,
      )
    }
  }

  private fun build(
    file: VirtualFile,
    startLine: Int?,
    endLine: Int?,
    codeSnapshot: String?,
    offset: Int,
  ): SelectionSnapshot {
    // FileUtil.getRelativePath returns OS-native separators, so on Windows this yields
    // "src\main\Foo.kt". Everything downstream (VFS lookup for navigation and stale detection,
    // and the path the agent receives over MCP) needs forward slashes, so canonicalise here at the
    // single point where the stored path is produced.
    val relativePath = (
      project.basePath?.let { base ->
        com.intellij.openapi.util.io.FileUtil.getRelativePath(
          java.io.File(base),
          java.io.File(file.path),
        ) ?: file.path
      } ?: file.path
      ).let { FilePaths.canonical(it) }

    // Hash the FULL selected text, canonicalized exactly as StaleDetector re-derives it
// (trailing line separator stripped, CRLF normalized) so capture and detection always agree.
    val canonical = codeSnapshot?.let { Sha256.normalizeForComparison(it) }
    val contentSha = canonical?.let { Sha256.hex(it) }
    val (storedSnapshot, truncated) = truncate(codeSnapshot)

    return SelectionSnapshot(
      filePath = relativePath,
      language = file.fileType.name,
      startLine = startLine,
      endLine = endLine,
      codeSnapshot = storedSnapshot,
      contentSha256 = contentSha,
      truncated = truncated,
      symbolPath = if (codeSnapshot != null) SymbolPathResolver.resolve(project, file, offset) else null,
      // Git4Idea is an optional dependency; guard against NoClassDefFoundError when the plugin
      // is disabled so capture still returns a snapshot with vcsRevision = null.
      vcsRevision = try {
        VcsRevisionResolver.resolve(project, file)
      } catch (_: NoClassDefFoundError) {
        null
      },
    )
  }

  private fun truncate(text: String?): Pair<String?, Boolean> {
    if (text == null) return null to false
    if (text.length <= MAX_SNAPSHOT_CHARS) return text to false
    return text.take(MAX_SNAPSHOT_CHARS) to true
  }

  companion object {
    const val MAX_SNAPSHOT_CHARS = 64 * 1024

    fun getInstance(project: Project): SnapshotCaptureService = project.service<SnapshotCaptureService>()
  }
}