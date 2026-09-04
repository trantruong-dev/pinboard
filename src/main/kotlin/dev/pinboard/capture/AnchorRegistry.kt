package dev.pinboard.capture

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
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
  private val markers = mutableMapOf<String, RangeMarker>()
  private val watched = mutableSetOf<Document>()

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
      put(feedbackId, document.createRangeMarker(offsets.first, offsets.second))
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

        val snippet = feedback.codeSnapshot ?: continue
        val at = soleOccurrenceOf(text, snippet) ?: continue
        put(feedback.id, document.createRangeMarker(at, at + snippet.length))
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
    val marker = synchronized(lock) { markers[feedbackId] } ?: return null
    return ReadAction.compute<Pair<Int, Int>?, RuntimeException> {
      if (!marker.isValid) return@compute null
      linesFor(marker.document, marker.startOffset, marker.endOffset)
    }
  }

  /**
   * Writes every live marker's current position back into the store, and drops markers whose item
   * is gone.
   *
   * This is what makes the drift survive: the marker is runtime-only, so unless its position reaches
   * the store it is lost the moment the IDE closes.
   */
  fun syncToStore() {
    if (project.isDisposed) return
    val store = FeedbackStore.getInstance(project)
    val live = synchronized(lock) { markers.toMap() }
    val known = store.all().associateBy { it.id }

    for ((id, marker) in live) {
      if (id !in known) {
        drop(id)
        continue
      }
      val lines = ReadAction.compute<Pair<Int, Int>?, RuntimeException> {
        if (marker.isValid) linesFor(marker.document, marker.startOffset, marker.endOffset) else null
      }
      if (lines == null) {
        // An invalid marker means its range was deleted outright. Leave the stored lines alone and
        // let the stale check report it - that is exactly the case the snapshot exists for.
        drop(id)
        continue
      }
      store.updateLocation(id, lines.first, lines.second)
    }
  }

  /** True when [feedbackId] currently has a marker the platform still considers valid. */
  fun isAnchored(feedbackId: String): Boolean =
    synchronized(lock) { markers[feedbackId] }?.isValid == true

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

  private fun put(feedbackId: String, marker: RangeMarker) {
    synchronized(lock) { markers.put(feedbackId, marker) }?.dispose()
  }

  private fun drop(feedbackId: String) {
    synchronized(lock) { markers.remove(feedbackId) }?.dispose()
  }

  /**
   * Starts listening to [document] once, so edits eventually reach the store.
   *
   * The listener is registered against this service, so it goes away with the project rather than
   * outliving it and holding the document alive.
   */
  private fun watch(document: Document) {
    val isNew = synchronized(lock) { watched.add(document) }
    if (!isNew) return
    document.addDocumentListener(
      object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) = scheduleSync()
      },
      this,
    )
  }

  private fun scheduleSync() {
    if (project.isDisposed) return
    syncAlarm.cancelAllRequests()
    syncAlarm.addRequest({ syncToStore() }, SYNC_DELAY_MS)
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
   * A range ending exactly on a line start covers no text on that line - the capture side widens
   * selections to whole lines the same way, so the two conventions have to agree or a pin would
   * gain a line every time it round-trips.
   */
  private fun linesFor(document: Document, startOffset: Int, endOffset: Int): Pair<Int, Int> {
    val start = document.getLineNumber(startOffset.coerceIn(0, document.textLength))
    val clampedEnd = endOffset.coerceIn(startOffset, document.textLength)
    var end = document.getLineNumber(clampedEnd)
    if (clampedEnd > startOffset && end > start && clampedEnd == document.getLineStartOffset(end)) {
      end -= 1
    }
    return (start + 1) to (end + 1)
  }

  override fun dispose() {
    synchronized(lock) {
      markers.values.forEach { it.dispose() }
      markers.clear()
      watched.clear()
    }
  }

  companion object {
    private const val SYNC_DELAY_MS = 400

    fun getInstance(project: Project): AnchorRegistry =
      project.getService(AnchorRegistry::class.java)
  }
}
