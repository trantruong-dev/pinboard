package dev.pinboard.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status

class FeedbackStoreTest : BasePlatformTestCase() {

  private fun item(id: String, status: Status = Status.PENDING) = Feedback(
    id = id,
    status = status,
    scope = Scope.SELECTION,
    note = "note-$id",
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 1,
    endLine = 2,
    codeSnapshot = "code",
    contentSha256 = "sha",
    symbolPath = "sym",
    vcsRevision = null,
    thread = emptyList(),
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
  )

  fun testCrud() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()

    val created = store.add(item("1"))
    assertEquals(Status.PENDING, store.byId("1")!!.status)
    assertEquals(1, store.all().size)
    assertEquals(1, store.byStatus(Status.PENDING).size)

    store.updateStatus("1", Status.ACKNOWLEDGED)
    assertEquals(Status.ACKNOWLEDGED, store.byId("1")!!.status)

    val replied = store.appendMessage("1", Author.AGENT, "summary done")
    assertEquals(1, replied!!.thread.size)
    assertEquals("summary done", replied.thread.first().body)

    assertTrue(store.delete("1"))
    assertNull(store.byId("1"))
  }

  fun testDeleteResolvedOnlyTouchesResolvedAndDismissed() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()

    store.add(item("pending", Status.PENDING))
    store.add(item("acked", Status.ACKNOWLEDGED))
    store.add(item("resolved", Status.RESOLVED))
    store.add(item("dismissed", Status.DISMISSED))

    val deleted = store.deleteResolved()

    assertEquals("only RESOLVED + DISMISSED removed", 2, deleted)
    assertNotNull("PENDING untouched", store.byId("pending"))
    assertNotNull("ACKNOWLEDGED untouched", store.byId("acked"))
    assertNull("RESOLVED removed", store.byId("resolved"))
    assertNull("DISMISSED removed", store.byId("dismissed"))
  }

  fun testUpdateNoteOnPendingItem() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    // updatedAt starts at 0 rather than "now" so the bump is unambiguous: two calls to
    // currentTimeMillis() inside the same millisecond would make a "newer than before" assertion
    // flaky, and the bump is the whole reason FeedbackMerger lets an edit win.
    store.add(item("1").copy(updatedAt = 0L))

    val updated = store.updateNote("1", "  rewritten  ")

    assertNotNull(updated)
    assertEquals("note is trimmed before it is stored", "rewritten", updated!!.note)
    assertTrue("updatedAt must be bumped so the edit wins a merge", updated.updatedAt > 0L)
    assertEquals("rewritten", store.byId("1")!!.note)
    store.deleteAll()
  }

  fun testUpdateNoteRefusesEverythingButPending() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("acked", Status.ACKNOWLEDGED))
    store.add(item("resolved", Status.RESOLVED))
    store.add(item("dismissed", Status.DISMISSED))

    for (id in listOf("acked", "resolved", "dismissed")) {
      assertNull("$id must not be editable", store.updateNote(id, "rewritten"))
      assertEquals("$id must be left as it was", "note-$id", store.byId(id)!!.note)
    }
    store.deleteAll()
  }

  fun testUpdateNoteRefusesUnknownIdAndBlankNote() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1"))

    assertNull("unknown id", store.updateNote("nope", "rewritten"))
    assertNull("empty note", store.updateNote("1", ""))
    assertNull("whitespace-only note", store.updateNote("1", "   \n  "))
    assertEquals("note-1", store.byId("1")!!.note)
    store.deleteAll()
  }

  fun testUpdateNoteIsNoOpWhenUnchanged() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1").copy(updatedAt = 12345L))

    // Same text, and the same text with surrounding whitespace, are both non-edits.
    assertNotNull(store.updateNote("1", "note-1"))
    assertNotNull(store.updateNote("1", "  note-1  "))

    assertEquals("an unchanged note must not bump updatedAt", 12345L, store.byId("1")!!.updatedAt)
    store.deleteAll()
  }

  fun testPersistenceAcrossStoreInstances() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("persisted", Status.PENDING))
    // force a synchronous flush, then read back through a fresh file
    store.flushForTest()
    val file = StorePaths.storeFile(project)!!
    val onDisk = FeedbackStoreFile(file).readOrEmpty()
    assertEquals(1, onDisk.size)
    assertEquals("persisted", onDisk.single().id)
    store.deleteAll()
  }

  fun testListenerFiresOnMutation() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    var fired = 0
    val connection = project.messageBus.connect(testRootDisposable)
    connection.subscribe(FeedbackListener.TOPIC, object : FeedbackListener {
      override fun onChanged() {
        fired++
      }
    })
    store.add(item("listener"))
    assertTrue("listener fired on add", fired >= 1)
    store.deleteAll()
  }

  fun testConcurrentMutationDuringFlushDoesNotLoseItems() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()

    // One thread repeatedly flushes while the other mutates; after convergence, every id added
    // must still be present (H1 regression test: merge-back must reconcile against current
    // memory, not replace it).
    val added = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val stop = java.util.concurrent.atomic.AtomicBoolean(false)
    val flusher = Thread {
      while (!stop.get()) {
        store.flushForTest()
      }
    }.apply { isDaemon = true; start() }

    val mutator = Thread {
      try {
        repeat(200) { i ->
          val id = "race-$i"
          store.add(item(id))
          added.add(id)
        }
      } finally {
        stop.set(true)
      }
    }
    mutator.start()
    mutator.join()
    flusher.join()

    val finalIds = store.all().map { it.id }.toSet()
    for (id in added) {
      assertTrue("item $id must survive concurrent flush", id in finalIds)
    }
    store.deleteAll()
  }
}