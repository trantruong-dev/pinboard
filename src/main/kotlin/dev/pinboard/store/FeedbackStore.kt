package dev.pinboard.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.pinboard.util.FilePaths
import com.intellij.openapi.util.Disposer
import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Status
import dev.pinboard.util.Ulid
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-project in-memory store with debounced, merge-safe persistence.
 *
 * - All mutations are lock-guarded and publish [FeedbackListener.TOPIC].
 * - Writes are debounced (300ms) and executed off the EDT.
 * - Each flush goes through [FeedbackStoreFile.flush] which re-reads + merges when the on-disk
 *   mtime changed, so concurrent IDE processes on the same repo converge instead of clobbering.
 * - Deleted ids are remembered as tombstones for the session so a merge never resurrects an item
 *   the user (or the agent) removed locally.
 */
@Service(Service.Level.PROJECT)
class FeedbackStore(private val project: Project) : Disposable {

  private val file: FeedbackStoreFile? = StorePaths.storeFile(project)?.let { FeedbackStoreFile(it) }
  private val items = ConcurrentHashMap<String, Feedback>()
  private val tombstones = ConcurrentHashMap.newKeySet<String>()
  private val lock = ReentrantLock()
  // Platform-managed pool rather than a plugin-created thread: a thread spawned by plugin code
  // inherits the plugin class loader as its context class loader and keeps it alive, which is the
  // documented way plugins become impossible to unload.
  private val executor: ScheduledExecutorService =
    AppExecutorUtil.createBoundedScheduledExecutorService("PinboardStoreFlush", 1)
  private var dirty = false
  private var disposed = false

  init {
    Disposer.register(project, this)
    loadInitial()
  }

  fun add(feedback: Feedback): Feedback {
    lock.withLock {
      items[feedback.id] = feedback
      tombstones.remove(feedback.id)
      scheduleFlushLocked()
      publishLocked()
      return feedback
    }
  }

  fun all(): List<Feedback> = items.values.sortedBy { it.createdAt }

  fun byStatus(status: Status): List<Feedback> = all().filter { it.status == status }

  fun byId(id: String): Feedback? = items[id]

  fun updateStatus(id: String, newStatus: Status): Feedback? {
    lock.withLock {
      val current = items[id] ?: return null
      val updated = current.copyWithStatus(newStatus)
      items[id] = updated
      scheduleFlushLocked()
      publishLocked()
      return updated
    }
  }

  fun appendMessage(id: String, author: Author, body: String): Feedback? {
    lock.withLock {
      val current = items[id] ?: return null
      val message = Message(id = Ulid.generate(), author = author, body = body, createdAt = System.currentTimeMillis())
      val updated = current.copyWithMessage(message)
      items[id] = updated
      scheduleFlushLocked()
      publishLocked()
      return updated
    }
  }

  /**
   * Moves an item to a new line range, or does nothing if it is already there.
   *
   * This is how the runtime anchoring in [dev.pinboard.capture.AnchorRegistry] becomes durable: a
   * marker dies with the document, so unless its position lands here it is lost on close. The
   * no-op-when-unchanged part is load-bearing - a sync that always marked the store dirty would
   * schedule a flush, which syncs again, forever.
   */
  fun updateLocation(id: String, startLine: Int, endLine: Int): Feedback? {
    lock.withLock {
      val current = items[id] ?: return null
      if (current.startLine == startLine && current.endLine == endLine) return current
      val updated = current.copy(startLine = startLine, endLine = endLine)
      items[id] = updated
      scheduleFlushLocked()
      publishLocked()
      return updated
    }
  }

  fun delete(id: String): Boolean {
    lock.withLock {
      if (items.remove(id) == null) return false
      tombstones.add(id)
      scheduleFlushLocked()
      publishLocked()
      return true
    }
  }

  fun deleteAll(): Int {
    lock.withLock {
      val count = items.size
      tombstones.addAll(items.keys)
      items.clear()
      if (count > 0) {
        scheduleFlushLocked()
        publishLocked()
      }
      return count
    }
  }

  /**
   * Deletes only RESOLVED and DISMISSED items. PENDING and ACKNOWLEDGED are never touched -
   * this is the hard guard behind the MCP tool `feedback_clear_resolved`.
   */
  fun deleteResolved(): Int {
    lock.withLock {
      val doomed = items.values.filter { it.status == Status.RESOLVED || it.status == Status.DISMISSED }
      if (doomed.isEmpty()) return 0
      doomed.forEach { tombstones.add(it.id); items.remove(it.id) }
      scheduleFlushLocked()
      publishLocked()
      return doomed.size
    }
  }

  private fun loadInitial() {
    val loaded = file?.readOrEmpty() ?: return
    var migrated = false
    loaded.forEach { feedback ->
      val canonical = withCanonicalPath(feedback)
      if (canonical !== feedback) migrated = true
      items[canonical.id] = canonical
    }
    // Items written before paths were normalised still carry OS-native separators. Rewriting them
    // here keeps one shape everywhere: what the agent receives over MCP, what the tool window
    // renders, and what gets persisted on the next flush.
    if (migrated) {
      lock.withLock {
        dirty = true
        scheduleFlushLocked()
      }
    }
  }

  /** Returns [feedback] unchanged, or a copy whose [Feedback.filePath] uses forward slashes. */
  private fun withCanonicalPath(feedback: Feedback): Feedback {
    val path = feedback.filePath ?: return feedback
    val canonical = FilePaths.canonical(path)
    return if (canonical == path) feedback else feedback.copy(filePath = canonical)
  }

  private fun scheduleFlushLocked() {
    dirty = true
    if (disposed) return
    executor.schedule({ flush() }, 300, TimeUnit.MILLISECONDS)
  }

  private fun flush() {
    if (disposed) return
    syncAnchorsBeforeWriting()
    // Snapshot the memory state under lock. We do NOT clear dirty here: a failed write must
    // leave dirty set so a later flush (or dispose) retries.
    val snapshot = lock.withLock { items.values.toList() }
    // Filter tombstoned ids out of the snapshot BEFORE persisting so a delete never lands on
    // disk even transiently (the merge only filters the disk side, not the memory side).
    val snapshotFiltered = snapshot.filter { it.id !in tombstones }
    val file = file ?: return
    try {
      val persisted = file.flush(snapshotFiltered, tombstones)
      lock.withLock {
        // If dispose ran while we were writing, skip the merge-back: dispose already performed
        // its own final flush of the then-current memory. Mutating here would clobber it.
        if (disposed) return
        // Reconcile the persisted set against CURRENT memory: any mutation that landed during
        // the flush IO window (add/updateStatus/appendMessage/delete) must win over the stale
        // snapshot we just persisted. Tombstones filter the persisted side so a delete that
        // happened mid-window cannot resurrect its item.
        val reconciled = FeedbackMerger.merge(persisted, items.values.toList(), tombstones)
        items.clear()
        reconciled.forEach { items[it.id] = it }
        // If a mutation landed mid-window, memory now diverges from what we just persisted -
        // keep dirty so the flush scheduled by that mutation (or dispose) writes it out.
        dirty = reconciled != persisted
      }
    } catch (e: Exception) {
      // Keep dirty=true so the next scheduled flush or dispose retries; never lose the snapshot.
      lock.withLock { dirty = true }
    }
  }

  /**
   * Folds any live anchoring back in, so what reaches disk is where the code is now rather than
   * where it was when it was pinned.
   *
   * Runs before the lock is taken: the write-back goes through [updateLocation], which takes the
   * same lock itself. Anchoring is an optimisation, so a failure here must never stop a write.
   */
  private fun syncAnchorsBeforeWriting() {
    if (project.isDisposed) return
    try {
      dev.pinboard.capture.AnchorRegistry.getInstance(project).syncToStore()
    } catch (e: Exception) {
      LOG.debug("Anchor sync before flush failed", e)
    }
  }

  private fun publishLocked() {
    if (disposed) return
    project.messageBus.syncPublisher(FeedbackListener.TOPIC).onChanged()
  }

  /** Synchronously flushes pending changes to disk. Test-only; production uses the debounced path. */
  fun flushForTest() {
    flush()
  }

  override fun dispose() {
    lock.withLock {
      disposed = true
      if (dirty) {
        val snapshot = items.values.toList()
        try {
          file?.flush(snapshot, tombstones)
        } catch (_: Exception) {
          // Best-effort final flush; nothing left to retry on IDE close.
        }
      }
      executor.shutdownNow()
    }
  }

  companion object {
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(FeedbackStore::class.java)

    fun getInstance(project: Project): FeedbackStore = project.service<FeedbackStore>()
  }
}