package dev.pinboard.capture

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.mcp.StaleDetector
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import dev.pinboard.util.Sha256
import java.nio.file.Files
import java.nio.file.Path

class AnchorRegistryTest : BasePlatformTestCase() {

  private val registry get() = AnchorRegistry.getInstance(project)
  private val store get() = FeedbackStore.getInstance(project)

  override fun setUp() {
    super.setUp()
    // The light-project fixture is shared across test classes, so the queue arrives dirty.
    store.deleteAll()
  }

  override fun tearDown() {
    try {
      store.deleteAll()
    } finally {
      super.tearDown()
    }
  }

  private fun pin(
    id: String,
    path: String,
    startLine: Int,
    endLine: Int,
    snippet: String,
  ): Feedback {
    val now = System.currentTimeMillis()
    return store.add(
      Feedback(
        id = id,
        status = Status.PENDING,
        scope = Scope.SELECTION,
        note = "note",
        filePath = path,
        language = "Kotlin",
        startLine = startLine,
        endLine = endLine,
        codeSnapshot = snippet,
        contentSha256 = Sha256.hex(Sha256.normalizeForComparison(snippet)),
        symbolPath = null,
        vcsRevision = null,
        createdAt = now,
        updatedAt = now,
      ),
    )
  }

  /**
   * Creates a real file under the project root.
   *
   * The fixture's in-memory files do not live under `project.basePath`, so
   * [dev.pinboard.util.ProjectFiles] cannot resolve them - and resolution is exactly what the code
   * under test depends on. This is the production shape.
   */
  private fun documentFor(path: String, text: String): Document {
    val onDisk = Path.of(project.basePath!!).resolve(path)
    Files.createDirectories(onDisk.parent)
    Files.writeString(onDisk, text)
    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(onDisk.toString())!!
    return FileDocumentManager.getInstance().getDocument(file)!!
  }

  private fun edit(document: Document, change: (Document) -> Unit) {
    WriteCommandAction.runWriteCommandAction(project) { change(document) }
  }

  /**
   * The whole point of anchoring. Inserting a line above a pin used to shift its stored line
   * numbers out from under it and report the pin stale, even though its own code was never touched.
   */
  fun testInsertingALineAboveMovesThePinAndDoesNotMakeItStale() {
    val document = documentFor("src/anchors/Drift.kt", "val a = 1\nval target = 2\nval c = 3\n")
    pin("drift", "src/anchors/Drift.kt", 2, 2, "val target = 2")
    registry.anchorAt("drift", document, 2, 2)

    edit(document) { it.insertString(0, "val inserted = 0\n") }

    assertEquals(3 to 3, registry.lineRange("drift"))
    assertFalse("pinned code is untouched", StaleDetector.check(project, store.byId("drift")!!).stale)
  }

  /** Editing the pinned lines themselves is the case staleness exists to report. */
  fun testEditingThePinnedLinesIsStale() {
    val document = documentFor("src/anchors/Edited.kt", "val a = 1\nval target = 2\nval c = 3\n")
    pin("edited", "src/anchors/Edited.kt", 2, 2, "val target = 2")
    registry.anchorAt("edited", document, 2, 2)

    edit(document) {
      val line = it.getLineStartOffset(1)
      it.replaceString(line, it.getLineEndOffset(1), "val target = 999")
    }

    assertTrue("the pinned code changed", StaleDetector.check(project, store.byId("edited")!!).stale)
  }

  /**
   * A short snippet like `return null` matches all over a real file. Anchoring to whichever copy
   * happens to come first would silently move a note onto unrelated code, which is worse than
   * leaving it where it was and letting the stale check speak.
   */
  fun testASnippetThatMatchesInSeveralPlacesIsNotAnchored() {
    val document = documentFor(
      "src/anchors/Ambiguous.kt",
      "fun a() {\n  return null\n}\nfun b() {\n  return null\n}\n",
    )
    pin("ambiguous", "src/anchors/Ambiguous.kt", 2, 2, "  return null")

    registry.ensureAnchored(document)

    assertFalse("must refuse rather than guess", registry.isAnchored("ambiguous"))
  }

  fun testAUniqueSnippetIsFoundAgainAfterTheMarkerIsGone() {
    val document = documentFor("src/anchors/Unique.kt", "val a = 1\nval unique = 2\nval c = 3\n")
    pin("unique", "src/anchors/Unique.kt", 2, 2, "val unique = 2")

    registry.ensureAnchored(document)

    assertTrue(registry.isAnchored("unique"))
    assertEquals(2 to 2, registry.lineRange("unique"))
  }

  /** The snippet is gone from the file: nothing to anchor to, so the stored lines still stand. */
  fun testASnippetThatNoLongerExistsFallsBackToTheStoredLines() {
    val document = documentFor("src/anchors/Gone.kt", "val a = 1\nval b = 2\n")
    pin("gone", "src/anchors/Gone.kt", 2, 2, "val vanished = 42")

    registry.ensureAnchored(document)

    assertFalse(registry.isAnchored("gone"))
    assertNull(registry.lineRange("gone"))
    assertEquals(2, store.byId("gone")!!.startLine)
  }

  /**
   * A marker dies with its document, so a position that never reaches the store is lost on close.
   * This is what makes the drift outlive the session.
   */
  fun testSyncWritesTheMovedPositionBackIntoTheStore() {
    val document = documentFor("src/anchors/Synced.kt", "val a = 1\nval target = 2\n")
    pin("synced", "src/anchors/Synced.kt", 2, 2, "val target = 2")
    registry.anchorAt("synced", document, 2, 2)

    edit(document) { it.insertString(0, "val x = 0\nval y = 0\n") }
    registry.syncToStore()

    assertEquals(4, store.byId("synced")!!.startLine)
    assertEquals(4, store.byId("synced")!!.endLine)
  }

  /**
   * Sync marks the store dirty, which schedules a flush, which syncs again. If a sync that changed
   * nothing still wrote, that loop would never settle.
   */
  fun testSyncingAnUnmovedPinChangesNothing() {
    val document = documentFor("src/anchors/Still.kt", "val a = 1\nval target = 2\n")
    val pinned = pin("still", "src/anchors/Still.kt", 2, 2, "val target = 2")
    registry.anchorAt("still", document, 2, 2)

    registry.syncToStore()

    assertSame("the item must not be rewritten", pinned, store.byId("still"))
  }

  /** Deleting the pinned range outright leaves nothing to anchor to. */
  fun testDeletingThePinnedRangeInvalidatesTheAnchor() {
    val document = documentFor("src/anchors/Deleted.kt", "val a = 1\nval target = 2\nval c = 3\n")
    pin("deleted", "src/anchors/Deleted.kt", 2, 2, "val target = 2")
    registry.anchorAt("deleted", document, 2, 2)

    edit(document) { it.deleteString(0, it.textLength) }

    assertNull(registry.lineRange("deleted"))
    assertTrue(StaleDetector.check(project, store.byId("deleted")!!).stale)
  }

  /** A marker for an item that no longer exists is a document reference nobody can reach. */
  fun testSyncDropsMarkersForDeletedItems() {
    val document = documentFor("src/anchors/Dropped.kt", "val a = 1\nval target = 2\n")
    pin("dropped", "src/anchors/Dropped.kt", 2, 2, "val target = 2")
    registry.anchorAt("dropped", document, 2, 2)
    assertTrue(registry.isAnchored("dropped"))

    store.delete("dropped")
    registry.syncToStore()

    assertFalse(registry.isAnchored("dropped"))
  }

  /**
   * Everything above is an overlay. With no anchor the detector must behave exactly as it did
   * before anchoring existed, because that is the state every item is in after a restart.
   */
  fun testWithoutAnAnchorStalenessIsDecidedByTheStoredLinesAlone() {
    val document = documentFor("src/anchors/NoAnchor.kt", "val a = 1\nval target = 2\n")
    pin("noanchor", "src/anchors/NoAnchor.kt", 2, 2, "val target = 2")

    assertFalse(registry.isAnchored("noanchor"))
    assertFalse(StaleDetector.check(project, store.byId("noanchor")!!).stale)

    edit(document) { it.insertString(0, "val inserted = 0\n") }

    // No marker, so line 2 now holds different text: the pre-anchoring behaviour, unchanged.
    assertTrue(StaleDetector.check(project, store.byId("noanchor")!!).stale)
  }

  fun testWholeFileItemsAreNeverAnchored() {
    val document = documentFor("src/anchors/WholeFile.kt", "val a = 1\n")
    val now = System.currentTimeMillis()
    store.add(
      Feedback(
        id = "whole",
        status = Status.PENDING,
        scope = Scope.FILE,
        note = "note",
        filePath = "src/anchors/WholeFile.kt",
        language = "Kotlin",
        startLine = null,
        endLine = null,
        codeSnapshot = null,
        contentSha256 = null,
        symbolPath = null,
        vcsRevision = null,
        createdAt = now,
        updatedAt = now,
      ),
    )

    registry.ensureAnchored(document)

    assertFalse(registry.isAnchored("whole"))
  }

  /** Guards the offset/line round trip: a pin must not gain a line each time it is synced. */
  fun testAMultiLinePinKeepsItsExtentAcrossSyncs() {
    val document = documentFor("src/anchors/Multi.kt", "val a = 1\nval b = 2\nval c = 3\nval d = 4\n")
    pin("multi", "src/anchors/Multi.kt", 2, 3, "val b = 2\nval c = 3")
    registry.anchorAt("multi", document, 2, 3)

    repeat(3) { registry.syncToStore() }

    assertEquals(2 to 3, registry.lineRange("multi"))
    assertEquals(2, store.byId("multi")!!.startLine)
    assertEquals(3, store.byId("multi")!!.endLine)
  }
}
