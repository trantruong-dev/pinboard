package dev.pinboard.capture

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.store.FeedbackStore
import dev.pinboard.util.FilePaths
import dev.pinboard.util.ProjectFiles

/**
 * Keeps pinned ranges pointing at the code they were pinned to while the file is open.
 *
 * Stored positions are line numbers, so inserting a single line above a pin shifts every pin below
 * it and reports them all stale - even though none of that code changed. A [RangeMarker] is moved by
 * the platform on every document edit, so the pin travels with the code instead of breaking.
 *
 * What this cannot do, and should not be sold as doing: markers only live while a document is
 * loaded. Close the IDE and they are gone. So this is a runtime overlay on the stored line numbers,
 * never a replacement for them - [Feedback.codeSnapshot] and [Feedback.contentSha256] stay the
 * durable record, and re-anchoring after a restart is allowed to fail.
 */
@Service(Service.Level.PROJECT)
class AnchorRegistry(private val project: Project) : Disposable {

  private val lock = Any()
  private val anchors = mutableMapOf<String, Anchor>()

  /**
   * A marker and the document it was created in.
   *
   * The document is kept rather than read back from the marker because `RangeMarker.getDocument`
   * is a model read, and cleanup runs on the store's flush thread, which holds no read lock.
   */
  private data class Anchor(val marker: RangeMarker, val document: Document)

  /**
   * One listener per document, disposed when that document's last marker goes.
   *
   * Without the removal half, a document stays referenced - by its listener and by this map - for
   * the project's whole life, which keeps its text and marker tree resident long after the file was
   * closed and every pin in it deleted.
   */
  private val watchers = mutableMapOf<Document, Disposable>()

  /**
   * Edits arrive on the EDT inside a write action, where the store must not be touched - publishing
   * from there would rebuild the queue mid-write. The write-back is deferred and coalesced instead.
   */
  private val syncAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  /**
   * Anchors [feedbackId] at a known line range. Used at capture, where the exact range is in hand
   * and there is nothing to search for.
   */
  fun anchorAt(feedbackId: String, document: Document, startLine: Int, endLine: Int) {
    ReadAction.run<RuntimeException> {
      val offsets = offsetsFor(document, startLine, endLine) ?: return@run
      put(feedbackId, document.createRangeMarker(offsets.first, offsets.second), document)
      watch(document)
    }
  }

  /**
   * Re-anchors any item pointing into [document] that has no live marker, by finding its captured
   * snippet in the current text.
   *
   * A snippet that appears in more than one place is refused rather than guessed at. `return null`
   * matches dozens of spots in a real file, and silently anchoring a note to the wrong one of them
   * is worse than leaving it on its stored line where the stale check can speak up.
   */
  fun ensureAnchored(document: Document) {
    ReadAction.run<RuntimeException> {
      val file = FileDocumentManager.getInstance().getFile(document) ?: return@run
      val relativePath = ProjectFiles.relativePath(project, file) ?: return@run
      val text = document.text
      var anchoredAny = false

      for (feedback in FeedbackStore.getInstance(project).all()) {
        if (!isAnchorable(feedback)) continue
        if (FilePaths.canonical(feedback.filePath!!) != relativePath) continue
        if (isAnchored(feedback.id)) continue

        // Captures are widened to whole lines, so a snippet usually ends with a newline. Searching
        // for it including that newline would put the marker's end on the next line's start offset,
        // which is a different range from the one anchorAt builds for the same lines.
        val snippet = feedback.codeSnapshot?.trimEnd('\n', '\r')?.takeIf { it.isNotEmpty() } ?: continue
        val at = soleOccurrenceOf(text, snippet) ?: continue
        put(feedback.id, document.createRangeMarker(at, at + snippet.length), document)
        anchoredAny = true
      }
      if (anchoredAny) watch(document)
    }
  }

  /**
   * The item's current 1-based inclusive line range, or null when it has no live marker.
   *
   * Callers fall back to the stored lines on null. That fallback is the normal case right after a
   * restart, not an error.
   */
  fun lineRange(feedbackId: String): Pair<Int, Int>? {
    val anchor = synchronized(lock) { anchors[feedbackId] } ?: return null
    return ReadAction.compute<Pair<Int, Int>?, RuntimeException> {
      if (!anchor.marker.isValid) return@compute null
      linesFor(anchor.document, anchor.marker.startOffset, anchor.marker.endOffset)
    }
  }

  /**
   * Writes every live marker's current position back into the store, and drops markers whose item
   * is gone.
   *
   * This is what makes the drift survive: the marker is runtime-only, so unless its position reaches
   * the store it is lost the moment the IDE closes.
   *
   * The whole batch goes through the store in one call. One edit above a file's pins moves all of
   * them at once, and updating them one at a time would publish, and rewrite the store file, once
   * per pin.
   */
  fun syncToStore() {
    if (project.isDisposed) return
    val store = try {
      FeedbackStore.getInstance(project)
    } catch (e: ProcessCanceledException) {
      throw e
    } catch (e: Exception) {
      // The project can be disposed between the check above and this lookup.
      LOG.debug("Store unavailable while syncing anchors", e)
      return
    }

    val live = synchronized(lock) { anchors.toMap() }
    val known = store.all().mapTo(mutableSetOf()) { it.id }
    val moved = mutableMapOf<String, Pair<Int, Int>>()

    for ((id, anchor) in live) {
      if (id !in known) {
        drop(id)
        continue
      }
      val lines = ReadAction.compute<Pair<Int, Int>?, RuntimeException> {
        val marker = anchor.marker
        if (marker.isValid) linesFor(anchor.document, marker.startOffset, marker.endOffset) else null
      }
      if (lines == null) {
        // An invalid marker means its range was deleted outright. Leave the stored lines alone and
        // let the stale check report it - that is exactly the case the snapshot exists for.
        drop(id)
        continue
      }
      moved[id] = lines
    }
    if (moved.isNotEmpty()) store.updateLocations(moved)
  }

  /**
   * Forgets [feedbackId]'s anchor.
   *
   * Needed because an anchor can be created before the item is: the capture balloon anchors up
   * front so the pin cannot drift while the note is being typed, and a cancelled balloon has to
   * take its marker back with it.
   */
  fun release(feedbackId: String) = drop(feedbackId)

  /** True when [feedbackId] currently has a marker the platform still considers valid. */
  fun isAnchored(feedbackId: String): Boolean =
    synchronized(lock) { anchors[feedbackId] }?.marker?.isValid == true

  private fun isAnchorable(feedback: Feedback): Boolean =
    feedback.scope == Scope.SELECTION &&
      feedback.filePath != null &&
      feedback.startLine != null &&
      feedback.endLine != null

  /**
   * The offset of [snippet] in [text] when it occurs exactly once, else null.
   *
   * Deliberately not "the first match": see [ensureAnchored].
   */
  private fun soleOccurrenceOf(text: String, snippet: String): Int? {
    if (snippet.isEmpty()) return null
    val first = text.indexOf(snippet)
    if (first < 0) return null
    if (text.indexOf(snippet, first + 1) >= 0) return null
    return first
  }

  private fun put(feedbackId: String, marker: RangeMarker, document: Document) {
    val replaced = synchronized(lock) { anchors.put(feedbackId, Anchor(marker, document)) }
    replaced?.let { retire(it) }
  }

  private fun drop(feedbackId: String) {
    val removed = synchronized(lock) { anchors.remove(feedbackId) } ?: return
    retire(removed)
  }

  /**
   * Disposes a marker and stops watching its document once nothing else is anchored there.
   *
   * Touches no model state, so it is safe from the store's flush thread, which holds no read
   * lock - that is why the document travels with the marker instead of being read back from it.
   */
  private fun retire(anchor: Anchor) {
    anchor.marker.dispose()
    val watcher = synchronized(lock) {
      if (anchors.values.any { it.document === anchor.document }) {
        null
      } else {
        watchers.remove(anchor.document)
      }
    }
    watcher?.let { Disposer.dispose(it) }
  }

  /**
   * Starts listening to [document] once, so edits eventually reach the store.
   *
   * The listener hangs off a per-document [Disposable] registered under this service, so it goes
   * away either with the project or with the document's last marker, whichever comes first.
   */
  private fun watch(document: Document) {
    val watcher = synchronized(lock) {
      if (document in watchers) return
      Disposer.newDisposable("PinboardAnchorWatcher").also { watchers[document] = it }
    }
    Disposer.register(this, watcher)
    document.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = scheduleSync()
      },
      watcher,
    )
  }

  private fun scheduleSync() {
    if (project.isDisposed) return
    syncAlarm.cancelAllRequests()
    syncAlarm.addRequest(
      {
        // The alarm fires on a pooled thread long after the edit. The project can be gone by then,
        // and an unguarded throw here surfaces as an IDE internal error rather than a no-op.
        try {
          syncToStore()
        } catch (e: ProcessCanceledException) {
          throw e
        } catch (e: Exception) {
          LOG.debug("Deferred anchor sync failed", e)
        }
      },
      SYNC_DELAY_MS,
    )
  }

  /** Stored lines are 1-based inclusive; document lines are 0-based. Everything is clamped. */
  private fun offsetsFor(document: Document, startLine: Int, endLine: Int): Pair<Int, Int>? {
    if (document.lineCount == 0) return null
    val first = (startLine - 1).coerceIn(0, document.lineCount - 1)
    val last = (endLine - 1).coerceIn(first, document.lineCount - 1)
    return document.getLineStartOffset(first) to document.getLineEndOffset(last)
  }

  /**
   * The inverse of [offsetsFor].
   *
   * A range ending exactly at the start of a non-empty line covers no text on that line, so that
   * line does not belong to the pin - otherwise a pin would gain a line every time it round-trips.
   * An empty line is excluded from that rule because its start and end offsets are the same value,
   * which would make the last line of a pin ending on a blank line disappear instead.
   */
  private fun linesFor(document: Document, startOffset: Int, endOffset: Int): Pair<Int, Int> {
    val start = document.getLineNumber(startOffset.coerceIn(0, document.textLength))
    val clampedEnd = endOffset.coerceIn(startOffset, document.textLength)
    var end = document.getLineNumber(clampedEnd)
    val endLineIsEmpty = document.getLineStartOffset(end) == document.getLineEndOffset(end)
    if (clampedEnd > startOffset && end > start && !endLineIsEmpty &&
      clampedEnd == document.getLineStartOffset(end)
    ) {
      end -= 1
    }
    return (start + 1) to (end + 1)
  }

  override fun dispose() {
    synchronized(lock) {
      anchors.values.forEach { it.marker.dispose() }
      anchors.clear()
      watchers.clear()
    }
  }

  companion object {
    private const val SYNC_DELAY_MS = 400
    private val LOG = Logger.getInstance(AnchorRegistry::class.java)

    fun getInstance(project: Project): AnchorRegistry =
      project.getService(AnchorRegistry::class.java)
  }
}
