package dev.pinboard.mcp

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.util.Sha256
import java.nio.file.Files
import java.nio.file.Path

class StaleDetectorTest : BasePlatformTestCase() {

  /** Creates a real on-disk file under the project base dir (production shape). */
  private fun createOnDiskFile(relPath: String, content: String): Path {
    val base = Path.of(project.basePath!!)
    val path = base.resolve(relPath)
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
    LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
    return path
  }

  private fun feedback(
    filePath: String,
    startLine: Int,
    endLine: Int,
    codeSnapshot: String,
    scope: Scope = Scope.SELECTION,
  ) = Feedback(
    id = "id",
    status = Status.PENDING,
    scope = scope,
    note = "n",
    filePath = filePath,
    language = "Kotlin",
    startLine = startLine,
    endLine = endLine,
    codeSnapshot = codeSnapshot,
    // Match production: SnapshotCaptureService hashes the canonical (normalized) text, so the
    // stored hash always reflects the comparison form StaleDetector re-derives.
    contentSha256 = Sha256.hex(Sha256.normalizeForComparison(codeSnapshot)),
    truncated = false,
    symbolPath = null,
    vcsRevision = null,
    thread = emptyList(),
    createdAt = 1,
    updatedAt = 1,
  )

  fun testUnchangedFileIsNotStale() {
    createOnDiskFile("src/A.kt", "fun a1() {}\nfun a2() {}\nfun a3() {}\n")
    val result = StaleDetector.check(project, feedback("src/A.kt", 2, 2, "fun a2() {}"))
    assertFalse("unchanged file must not be stale", result.stale)
    assertFalse(result.fileMissing)
  }

  fun testModifiedRangeIsStale() {
    createOnDiskFile("src/B.kt", "fun b1() {}\nfun b2() {}\nfun b3() {}\n")
    // Rewrite the file on disk after capture (production: agent edits it).
    createOnDiskFile("src/B.kt", "fun b1() {}\nfun b2() { /* changed */ }\nfun b3() {}\n")
    val result = StaleDetector.check(project, feedback("src/B.kt", 2, 2, "fun b2() {}"))
    assertTrue("changed range must be stale", result.stale)
    assertFalse(result.fileMissing)
  }

  fun testModifiedElsewhereIsNotStale() {
    createOnDiskFile("src/C.kt", "fun c1() {}\nfun c2() {}\nfun c3() {}\n")
    createOnDiskFile("src/C.kt", "fun c1() { /* changed */ }\nfun c2() {}\nfun c3() {}\n")
    val result = StaleDetector.check(project, feedback("src/C.kt", 2, 2, "fun c2() {}"))
    assertFalse("change outside range must not mark stale", result.stale)
  }

  fun testMissingFileIsStaleWithFlag() {
    val result = StaleDetector.check(project, feedback("src/Gone.kt", 1, 1, "x"))
    assertTrue("missing file must be stale", result.stale)
    assertTrue("missing file flagged", result.fileMissing)
  }

  fun testFileScopeNeverStale() {
    val result = StaleDetector.check(project, feedback("src/D.kt", 1, 1, "x", scope = Scope.FILE))
    assertFalse("FILE scope has no snapshot to compare", result.stale)
  }

  fun testRealCaptureThroughDetectorIsNotStale() {
    // Integration contract: a snapshot produced by SnapshotCaptureService (which may include a
    // trailing newline for select-all / whole-line captures) must not be reported stale when the
    // file is unchanged. Uses an on-disk file so the detector can resolve it via the VFS.
    createOnDiskFile("src/Int.kt", "fun i1() {}\nfun i2() {}\n")
    val path = project.baseDir!!.findFileByRelativePath("src/Int.kt")!!
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, path), true
    )!!
    val document = editor.document
    // Select the whole second line INCLUDING its trailing newline (Shift+Down shape).
    val lineStart = document.getLineStartOffset(1)
    val nextLineStart = document.getLineStartOffset(2)
    editor.selectionModel.setSelection(lineStart, nextLineStart)

    val snapshot = dev.pinboard.capture.SnapshotCaptureService.getInstance(project)
      .captureSelection(editor, path)!!
    val fb = feedback("src/Int.kt", snapshot.startLine!!, snapshot.endLine!!, snapshot.codeSnapshot!!)
    val result = StaleDetector.check(project, fb)
    assertFalse("real capture of unchanged file must not be stale", result.stale)
  }

  fun testWindowsSeparatorInStoredPathStillResolves() {
    // Items captured before the separator was normalised are still sitting in users' queues with
    // "src\\W.kt". Those must keep resolving, otherwise every one of them silently turns into
    // "file missing" and the agent is told to distrust code that never changed.
    createOnDiskFile("src/W.kt", "fun w1() {}\nfun w2() {}\n")
    val stored = "src" + '\\' + "W.kt"
    val result = StaleDetector.check(project, feedback(stored, 2, 2, "fun w2() {}"))
    assertFalse("backslash path must resolve, not report missing", result.fileMissing)
    assertFalse("unchanged content must not be stale", result.stale)
  }
}
