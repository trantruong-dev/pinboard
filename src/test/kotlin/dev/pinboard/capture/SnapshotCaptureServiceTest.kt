package dev.pinboard.capture

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.util.Sha256
import java.nio.file.Files

class SnapshotCaptureServiceTest : BasePlatformTestCase() {

  fun testCaptureSelectionProducesRelativePathAndHash() {
    val file = myFixture.addFileToProject("src/main/kotlin/com/example/Calculator.kt",
      """
      package com.example
      class Calculator {
        fun add(a: Int, b: Int): Int = a + b
      }
      """.trimIndent()
    )

    // Select the "add" line: get editor and set selection.
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!

    val document = editor.document
    val addLine = document.text.lineSequence().indexOfFirst { it.contains("fun add") }
    val start = document.getLineStartOffset(addLine)
    val end = document.getLineEndOffset(addLine)
    editor.selectionModel.setSelection(start, end)

    val service = SnapshotCaptureService.getInstance(project)
    val snapshot = service.captureSelection(editor, file.virtualFile)

    assertNotNull(snapshot)
    // The light test fixture mounts files outside the project basePath, so derive the expectation
    // from the same inputs production uses - but normalise the separator here, otherwise this
    // asserts FileUtil.getRelativePath against itself and passes whatever production produces.
    val expectedRelative = (
      com.intellij.openapi.util.io.FileUtil.getRelativePath(
        java.io.File(project.basePath!!), java.io.File(file.virtualFile.path)
      ) ?: file.virtualFile.path
      ).replace('\\', '/')
    assertEquals("filePath", expectedRelative, snapshot!!.filePath)
    assertFalse("stored path must never carry a Windows separator", snapshot.filePath!!.contains("\\"))
    assertTrue("relative path must not start with separator", !snapshot.filePath!!.startsWith("/"))
    assertEquals("Kotlin", snapshot.language)
    assertTrue(snapshot.startLine!! >= 1)
    assertTrue(snapshot.codeSnapshot!!.contains("fun add"))
    assertEquals(Sha256.hex(snapshot.codeSnapshot!!), snapshot.contentSha256)
    assertTrue("symbol path should include class and method", snapshot.symbolPath!!.contains("Calculator"))
  }

  fun testShaIsStableAcrossCaptures() {
    val file = myFixture.addFileToProject("src/A.kt", "fun a() {}")
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    val document = editor.document
    editor.selectionModel.setSelection(0, document.textLength)

    val service = SnapshotCaptureService.getInstance(project)
    val first = service.captureSelection(editor, file.virtualFile)!!
    val second = service.captureSelection(editor, file.virtualFile)!!
    assertEquals(first.contentSha256, second.contentSha256)
  }

  fun testNoSelectionReturnsNull() {
    val file = myFixture.addFileToProject("src/B.kt", "fun b() {}")
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    editor.selectionModel.removeSelection()
    val service = SnapshotCaptureService.getInstance(project)
    assertNull(service.captureSelection(editor, file.virtualFile))
  }

  fun testCaptureFileScope() {
    val file = myFixture.addFileToProject("src/C.kt", "fun c() {}")
    val service = SnapshotCaptureService.getInstance(project)
    val snapshot = service.captureFile(file.virtualFile)
    assertNotNull(snapshot)
    assertTrue("relative path must not start with separator", !snapshot!!.filePath!!.startsWith("/") && !snapshot.filePath!!.startsWith("\\"))
    assertNull("FILE scope has no snapshot", snapshot.codeSnapshot)
    assertNull(snapshot.startLine)
    assertNull(snapshot.contentSha256)
  }

  fun testSelectionEndingAtTrailingNewlineDoesNotOvercountEndLine() {
    val file = myFixture.addFileToProject("src/D.kt",
      "fun d1() {}\nfun d2() {}\nfun d3() {}\n"
    )
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    val document = editor.document
    // Select from line 0 start to very end (document ends with \n).
    editor.selectionModel.setSelection(0, document.textLength)

    val service = SnapshotCaptureService.getInstance(project)
    val snapshot = service.captureSelection(editor, file.virtualFile)!!

    // 3 real lines; endLine must be 3, not the phantom line 4.
    assertEquals("endLine must not overcount past the last real line", 3, snapshot.endLine)
    assertEquals(1, snapshot.startLine)
  }

  fun testLargeSnapshotIsTruncatedButHashedFromFullText() {
    val file = myFixture.addFileToProject("src/E.kt", "// filler")
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    // Inject a > 64KB body into the document.
    val big = "fun e() {\n" + "x = 1; // padding text\n".repeat(6000) + "}\n"
    val document = editor.document
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
      document.setText(big)
    }
    editor.selectionModel.setSelection(0, document.textLength)

    val service = SnapshotCaptureService.getInstance(project)
    val snapshot = service.captureSelection(editor, file.virtualFile)!!

    assertTrue("snapshot must be capped", snapshot.codeSnapshot!!.length <= SnapshotCaptureService.MAX_SNAPSHOT_CHARS)
    assertTrue("truncated flag must be set", snapshot.truncated)
    // The hash covers the FULL selection (canonicalized the same way StaleDetector re-derives it),
    // not the truncated copy that gets stored, so stale detection stays correct on huge selections.
    val expected = dev.pinboard.util.Sha256.hex(dev.pinboard.util.Sha256.normalizeForComparison(big))
    assertEquals(expected, snapshot.contentSha256)
    val truncatedHash = dev.pinboard.util.Sha256.hex(
      dev.pinboard.util.Sha256.normalizeForComparison(snapshot.codeSnapshot!!)
    )
    assertFalse("hash must not be computed from the truncated snapshot", truncatedHash == snapshot.contentSha256)
  }

  fun testNestedPathUsesForwardSlashesOnEveryPlatform() {
    // FileUtil.getRelativePath returns OS-native separators. On Windows an un-normalised path
    // makes VirtualFile.findFileByRelativePath fail, which shows up as "file no longer exists"
    // in the tool window and as fileMissing=true over MCP for every file below the project root.
    val file = myFixture.addFileToProject("src/main/kotlin/dev/nested/Deep.kt", "fun deep() {}")
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    editor.selectionModel.setSelection(0, editor.document.textLength)

    val snapshot = SnapshotCaptureService.getInstance(project).captureSelection(editor, file.virtualFile)!!

    assertFalse("no Windows separator", snapshot.filePath!!.contains("\\"))
    assertTrue("nested path keeps its directories", snapshot.filePath!!.contains("/"))
    assertTrue("ends with the file name", snapshot.filePath!!.endsWith("Deep.kt"))
  }

  fun testMidLineSelectionIsWidenedToWholeLines() {
    // Double-clicking a symbol or dragging across an expression starts the selection mid-line.
    // The stored range is line-based and StaleDetector re-derives whole lines, so a snapshot that
    // kept the partial first line could never match and the item reported stale forever.
    val file = myFixture.addFileToProject("src/Mid.kt", "            val answer = 42\nfun f() {}\n")
    val editor = FileEditorManager.getInstance(project).openTextEditor(
      com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file.virtualFile), true
    )!!
    val text = editor.document.text
    val from = text.indexOf("val answer")
    editor.selectionModel.setSelection(from, from + "val answer = 42".length)

    val snapshot = SnapshotCaptureService.getInstance(project).captureSelection(editor, file.virtualFile)!!

    assertEquals("startLine", 1, snapshot.startLine)
    assertEquals("endLine", 1, snapshot.endLine)
    assertEquals("snapshot must cover the whole line, indentation included",
      "            val answer = 42", snapshot.codeSnapshot)
    // The hash must describe exactly what StaleDetector will re-derive from those lines.
    assertEquals(
      Sha256.hex(dev.pinboard.util.Sha256.normalizeForComparison("            val answer = 42")),
      snapshot.contentSha256,
    )
  }
}
