package dev.pinboard.store

import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FeedbackStoreFileTest {

  @Rule
  @JvmField
  val tmp: TemporaryFolder = TemporaryFolder()

  private fun item(
    id: String,
    status: Status = Status.PENDING,
    updatedAt: Long = System.currentTimeMillis(),
  ) = Feedback(
    id = id,
    status = status,
    scope = Scope.SELECTION,
    note = "note-$id",
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 1,
    endLine = 2,
    codeSnapshot = "fun foo() {}",
    contentSha256 = "abc",
    symbolPath = "com.foo.Bar",
    vcsRevision = null,
    thread = emptyList(),
    createdAt = 1,
    updatedAt = updatedAt,
  )

  private fun storeFile(): FeedbackStoreFile = FeedbackStoreFile(Path.of(tmp.root.path, "store.json"))

  @Test
  fun missingFileReturnsEmpty() {
    val f = storeFile()
    assertEquals(emptyList<Feedback>(), f.readOrEmpty())
  }

  @Test
  fun roundTripPreservesFields() {
    val f = storeFile()
    val original = listOf(
      item("1", Status.PENDING, 100),
      item("2", Status.RESOLVED, 200),
    )
    f.flush(original)
    val readBack = FeedbackStoreFile(Path.of(tmp.root.path, "store.json")).readOrEmpty()
    assertEquals(original.size, readBack.size)
    val back = readBack.first { it.id == "1" }
    assertEquals("src/Foo.kt", back.filePath)
    assertEquals("Kotlin", back.language)
    assertEquals(1, back.startLine)
    assertEquals("fun foo() {}", back.codeSnapshot)
    assertEquals("com.foo.Bar", back.symbolPath)
    assertTrue(back.thread.isEmpty())
    assertEquals(Status.PENDING, back.status)
    assertEquals(Scope.SELECTION, back.scope)
  }

  @Test
  fun corruptFileIsBackedUpAndReturnsEmpty() {
    val f = storeFile()
    Files.createDirectories(Path.of(tmp.root.path))
    Files.writeString(Path.of(tmp.root.path, "store.json"), "{ not valid json {{{")
    val result = f.readOrEmpty()
    assertEquals(emptyList<Feedback>(), result)
    assertTrue("corrupt file backed up", Files.exists(Path.of(tmp.root.path, "store.json.corrupt")))
    assertFalse("original not left behind", Files.exists(Path.of(tmp.root.path, "store.json")))
  }

  @Test
  fun atomicWriteLeavesNoTmpBehind() {
    val f = storeFile()
    f.flush(listOf(item("1", Status.PENDING, 100)))
    assertTrue("no .tmp leftover", !Files.exists(Path.of(tmp.root.path, "store.json.tmp")))
    assertTrue("store.json exists", Files.exists(Path.of(tmp.root.path, "store.json")))
  }

  @Test
  fun flushMergesConcurrentWritesFromDisk() {
    // Simulate: our memory has item 1; another "process" writes item 2 to disk.
    val f = storeFile()
    f.flush(listOf(item("1", Status.PENDING, 100)))

    // External writer appends item 2 directly (mtime changes).
    val external = FeedbackStoreFile(Path.of(tmp.root.path, "store.json"))
    external.flush(listOf(item("2", Status.PENDING, 200)))

    // Now our process flushes memory again; merge must keep both.
    val merged = f.flush(listOf(item("1", Status.PENDING, 100)))
    val ids = merged.map { it.id }.toSet()
    assertEquals(setOf("1", "2"), ids)
  }

  @Test
  fun newerUpdatedAtWinsMerge() {
    val f = storeFile()
    f.flush(listOf(item("1", Status.PENDING, 100)))
    val merged = f.flush(listOf(item("1", Status.RESOLVED, 200)))
    assertEquals(1, merged.size)
    assertEquals(Status.RESOLVED, merged.single().status)
  }

  @Test
  fun twoProcessesInterleaved200ItemsNoLoss() {
    val path = Path.of(tmp.root.path, "interleaved.json")
    val a = FeedbackStoreFile(path)
    val b = FeedbackStoreFile(path)

    // Process A writes items 0..99, process B writes 100..199, interleaved.
    val aBatch = (0 until 100).map { item("id-$it", Status.PENDING, it.toLong()) }
    val bBatch = (100 until 200).map { item("id-$it", Status.PENDING, it.toLong()) }

    var ai = 0
    var bi = 0
    repeat(200) { i ->
      if (i % 2 == 0) {
        a.flush(listOf(aBatch[ai++])); // simulate A reading fresh state each time
      } else {
        b.flush(listOf(bBatch[bi++]));
      }
    }

    val final = FeedbackStoreFile(path).readOrEmpty()
    assertEquals("all 200 items survive", 200, final.size)
    assertEquals(200, final.map { it.id }.toSet().size)
  }

  @Test
  fun tombstonePreventsResurrect() {
    val f = storeFile()
    f.flush(listOf(item("1", Status.PENDING, 100)))
    f.flush(emptyList(), tombstones = setOf("1"))
    val readBack = FeedbackStoreFile(Path.of(tmp.root.path, "store.json")).readOrEmpty()
    assertEquals(emptyList<Feedback>(), readBack)
  }
}