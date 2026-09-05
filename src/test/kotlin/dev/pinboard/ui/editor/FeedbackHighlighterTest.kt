package dev.pinboard.ui.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import dev.pinboard.util.ProjectFiles
import dev.pinboard.util.Sha256
import java.nio.file.Files
import java.nio.file.Path

class FeedbackHighlighterTest : BasePlatformTestCase() {

  private val store get() = FeedbackStore.getInstance(project)

  override fun setUp() {
    super.setUp()
    store.deleteAll()
  }

  override fun tearDown() {
    try {
      store.deleteAll()
    } finally {
      super.tearDown()
    }
  }

  private fun createOnDiskFile(relPath: String, text: String): Path {
    val path = Path.of(project.basePath!!).resolve(relPath)
    Files.createDirectories(path.parent)
    Files.writeString(path, text)
    LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
    return path
  }

  private fun pin(id: String, relPath: String, status: Status = Status.PENDING) {
    val now = System.currentTimeMillis()
    store.add(
      Feedback(
        id = id,
        status = status,
        scope = Scope.SELECTION,
        note = "note",
        filePath = relPath,
        language = "Kotlin",
        startLine = 1,
        endLine = 1,
        codeSnapshot = "val a = 1",
        contentSha256 = Sha256.hex(Sha256.normalizeForComparison("val a = 1")),
        symbolPath = null,
        vcsRevision = null,
        createdAt = now,
        updatedAt = now,
      ),
    )
  }

  private fun openEditors(relPath: String, count: Int): List<Editor> {
    val file = ProjectFiles.resolve(project, relPath)!!
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    return (1..count).map {
      EditorFactory.getInstance().createEditor(document, project, EditorKind.MAIN_EDITOR)
    }
  }

  private fun release(editors: List<Editor>) {
    editors.forEach { EditorFactory.getInstance().releaseEditor(it) }
  }

  private fun pinsIn(editor: Editor): Int =
    editor.markupModel.allHighlighters.count { it.gutterIconRenderer != null }

  /**
   * One file open in a split is two editors sharing one document, and highlighters live on an
   * editor's own markup model. Tracking what was installed per document instead of per editor made
   * the second editor's pass destroy the first editor's decorations, so only the last editor in the
   * platform's order kept them.
   */
  fun testASplitViewDecoratesEveryEditorNotJustTheLastOne() {
    createOnDiskFile("src/highlighter/Split.kt", "val a = 1\nval b = 2\n")
    pin("split", "src/highlighter/Split.kt")

    val editors = openEditors("src/highlighter/Split.kt", 2)
    try {
      FeedbackHighlighter.getInstance(project).refresh()
      assertEquals("first editor", 1, pinsIn(editors[0]))
      assertEquals("second editor", 1, pinsIn(editors[1]))
    } finally {
      release(editors)
    }
  }

  /** A second refresh must replace decorations, not stack a duplicate set on top of them. */
  fun testRefreshingTwiceDoesNotDoubleTheDecorations() {
    createOnDiskFile("src/highlighter/Twice.kt", "val a = 1\n")
    pin("twice", "src/highlighter/Twice.kt")

    val editors = openEditors("src/highlighter/Twice.kt", 1)
    try {
      val highlighter = FeedbackHighlighter.getInstance(project)
      highlighter.refresh()
      highlighter.refresh()
      assertEquals(1, pinsIn(editors[0]))
    } finally {
      release(editors)
    }
  }

  /**
   * A resolved item is history. Decorating it would leave marks over code that has already been
   * dealt with, and the whole point of the marks is to show what is still owed.
   */
  fun testOnlyOpenItemsAreDecorated() {
    createOnDiskFile("src/highlighter/Closed.kt", "val a = 1\n")
    pin("open", "src/highlighter/Closed.kt", Status.ACKNOWLEDGED)
    pin("done", "src/highlighter/Closed.kt", Status.RESOLVED)
    pin("dropped", "src/highlighter/Closed.kt", Status.DISMISSED)

    val editors = openEditors("src/highlighter/Closed.kt", 1)
    try {
      FeedbackHighlighter.getInstance(project).refresh()
      assertEquals(1, pinsIn(editors[0]))
    } finally {
      release(editors)
    }
  }

  /** Resolving an item has to take its decoration with it on the next refresh. */
  fun testResolvingAnItemRemovesItsDecoration() {
    createOnDiskFile("src/highlighter/Resolved.kt", "val a = 1\n")
    pin("resolving", "src/highlighter/Resolved.kt")

    val editors = openEditors("src/highlighter/Resolved.kt", 1)
    try {
      val highlighter = FeedbackHighlighter.getInstance(project)
      highlighter.refresh()
      assertEquals(1, pinsIn(editors[0]))

      store.updateStatus("resolving", Status.RESOLVED)
      highlighter.refresh()
      assertEquals(0, pinsIn(editors[0]))
    } finally {
      release(editors)
    }
  }

  fun testTheTabOfAFileWithOpenWorkIsTinted() {
    createOnDiskFile("src/highlighter/Tinted.kt", "val a = 1\n")
    createOnDiskFile("src/highlighter/Plain.kt", "val a = 1\n")
    pin("tinted", "src/highlighter/Tinted.kt")

    val provider = FeedbackTabColorProvider()
    val tinted = ProjectFiles.resolve(project, "src/highlighter/Tinted.kt")!!
    val plain = ProjectFiles.resolve(project, "src/highlighter/Plain.kt")!!

    assertNotNull(provider.getEditorTabColor(project, tinted))
    assertNull(provider.getEditorTabColor(project, plain))
  }

  /** Finished work is not owed, so its file's tab goes back to normal. */
  fun testATabWithOnlyFinishedWorkIsNotTinted() {
    createOnDiskFile("src/highlighter/Finished.kt", "val a = 1\n")
    pin("finished", "src/highlighter/Finished.kt", Status.RESOLVED)

    val file = ProjectFiles.resolve(project, "src/highlighter/Finished.kt")!!
    assertNull(FeedbackTabColorProvider().getEditorTabColor(project, file))
  }
}
