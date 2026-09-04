package dev.pinboard.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore

class CaptureSelectionActionTest : BasePlatformTestCase() {

  private fun eventFor(dataContext: com.intellij.openapi.actionSystem.DataContext): AnActionEvent {
    return AnActionEvent.createFromDataContext("test", Presentation(), dataContext)
  }

  fun testSelectionActionEnabledOnlyWithSelection() {
    val file = myFixture.addFileToProject("src/Example.kt", "fun main() {\n  println(\"hi\")\n}\n")
    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file.virtualFile), true)!!

    val base = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.EDITOR, editor)
      .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
      .build()

    // No selection -> disabled.
    editor.selectionModel.removeSelection()
    val noSelEvent = eventFor(base)
    CaptureSelectionAction().update(noSelEvent)
    assertFalse("disabled without selection", noSelEvent.presentation.isEnabledAndVisible)

    // With selection -> enabled.
    editor.selectionModel.setSelection(0, 5)
    val selEvent = eventFor(base)
    CaptureSelectionAction().update(selEvent)
    assertTrue("enabled with selection", selEvent.presentation.isEnabledAndVisible)
  }

  fun testFileActionDisabledForDirectory() {
    val file = myFixture.addFileToProject("dir/Placeholder.kt", "x")
    val ctx = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile.parent)
      .build()
    val e = eventFor(ctx)
    CaptureFileAction().update(e)
    assertFalse("disabled for directory", e.presentation.isEnabledAndVisible)
  }

  fun testCaptureFileActionEnabledForTextFile() {
    val file = myFixture.addFileToProject("src/Other.kt", "fun other() {}")
    val ctx = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
      .build()
    val e = eventFor(ctx)
    CaptureFileAction().update(e)
    assertTrue("enabled for text file", e.presentation.isEnabledAndVisible)
  }

  fun testCaptureFileActionDisabledForBinaryFile() {
    // An archive file maps to the binary ARCHIVE file type in the platform.
    val file = myFixture.addFileToProject("assets/data.zip", "PK\u0003\u0004not really a zip")
    val vf = file.virtualFile
    assertTrue("archive must be binary file type", vf.fileType.isBinary)
    val ctx = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.VIRTUAL_FILE, vf)
      .build()
    val e = eventFor(ctx)
    CaptureFileAction().update(e)
    assertFalse("disabled for binary file", e.presentation.isEnabledAndVisible)
  }

  fun testCaptureFileActionDisabledForFileOutsideProjectRoot() {
    // Build a VirtualFile outside the project content roots via the local file system
    // (a system temp file is never part of the light project's content).
    val tmpPath = java.nio.file.Files.createTempFile("outside-project", ".kt")
    val localFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(tmpPath.toString())
    assertNotNull("temp file must resolve in LocalFileSystem", localFile)
    val ctx = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.VIRTUAL_FILE, localFile)
      .build()
    val e = eventFor(ctx)
    CaptureFileAction().update(e)
    assertFalse("disabled for file outside project", e.presentation.isEnabledAndVisible)
  }

  fun testStorePersistsFeedbackItem() {
    // The modal dialog cannot be driven headless; verify the store persistence path that
    // CaptureActionSupport uses end to end.
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    val now = System.currentTimeMillis()
    store.add(
      dev.pinboard.model.Feedback(
        id = dev.pinboard.util.Ulid.generate(),
        status = Status.PENDING,
        scope = dev.pinboard.model.Scope.SELECTION,
        note = "fix this",
        filePath = "src/Example.kt",
        language = "Kotlin",
        startLine = 1,
        endLine = 2,
        codeSnapshot = "fun main() {}",
        contentSha256 = dev.pinboard.util.Sha256.hex("fun main() {}"),
        truncated = false,
        symbolPath = "com.example.Main",
        vcsRevision = null,
        thread = emptyList(),
        createdAt = now,
        updatedAt = now,
      )
    )
    assertEquals(1, store.all().size)
    assertEquals("fix this", store.all().single().note)
    assertEquals(1, store.all().single().startLine)
    store.deleteAll()
  }

  /**
   * Both capture paths gate on the same [dev.pinboard.ui.dialog.FeedbackForm], so a blank note is
   * refused once, in one place. A pin with no note tells the agent nothing and cannot be acted on.
   */
  fun testABlankNoteIsRefusedByTheSharedForm() {
    val form = dev.pinboard.ui.dialog.FeedbackForm(null)
    assertFalse("nothing typed", form.hasNote())

    form.noteArea.text = "   " + System.lineSeparator() + "   "
    assertFalse("only whitespace", form.hasNote())

    form.noteArea.text = "  fix this  "
    assertTrue("real text", form.hasNote())
    assertEquals("fix this", form.note())
  }

  /**
   * The modal path reads its note through that same form rather than a text area of its own, which
   * is what stops the two capture paths from drifting apart on what counts as a usable note.
   */
  fun testTheDialogReadsItsNoteThroughTheSharedForm() {
    val dialog = dev.pinboard.ui.dialog.FeedbackInputDialog(project, null)
    try {
      assertEquals("", dialog.note)
      val form = dialog.preferredFocusedComponent as javax.swing.JTextArea
      form.text = "  fix this  "
      assertEquals("fix this", dialog.note)
    } finally {
      com.intellij.openapi.util.Disposer.dispose(dialog.disposable)
    }
  }
}
