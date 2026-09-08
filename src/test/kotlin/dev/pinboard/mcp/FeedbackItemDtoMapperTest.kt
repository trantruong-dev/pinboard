package dev.pinboard.mcp

import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit, no fixture: [FeedbackItemDtoMapper] touches only the model and kotlinx.serialization,
 * and a test that needed an IDE to run would stop enforcing that.
 *
 * The caps are private on purpose - what the agent is promised is "bounded, newest kept, told what
 * was dropped", not a number - so the assertions here are written against the promise and derive
 * the number from what the mapper actually returns.
 */
class FeedbackItemDtoMapperTest {

  private fun message(index: Int, body: String = "m$index") =
    Message(id = "m$index", author = Author.AGENT, body = body, createdAt = 1_757_260_800_000 + index)

  private fun feedback(
    note: String = "note",
    codeSnapshot: String? = "val x = 1",
    thread: List<Message> = emptyList(),
  ) = Feedback(
    id = "id",
    status = Status.PENDING,
    scope = Scope.SELECTION,
    note = note,
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 12,
    endLine = 20,
    codeSnapshot = codeSnapshot,
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
    thread = thread,
    createdAt = 1_757_260_800_000,
    updatedAt = 1_757_260_800_000,
  )

  /** The message cap, read back from the mapper rather than restated here. */
  private val threadCap: Int =
    FeedbackItemDtoMapper.toDto(feedback(thread = (1..1000).map { message(it) })).thread.size

  /** The body cap, likewise. The marker is appended after the cut, so measure the cut itself. The
   *  probe has to outrun the cap or it measures its own length instead. */
  private val bodyCap: Int =
    FeedbackItemDtoMapper.toDto(feedback(thread = listOf(message(1, "x".repeat(4 * 1024 * 1024)))))
      .thread.first().body.takeWhile { it == 'x' }.length

  /**
   * The shape assertions below derive the caps rather than restating them, so retuning a constant
   * does not fail the suite for no reason. That alone would let a constant be retuned to anything
   * at all, though - and the budget is the feature here, not an implementation detail - so the
   * magnitude is pinned once, here, loosely enough to allow a considered adjustment and tightly
   * enough that a body cap off by an order of magnitude cannot ship green.
   */
  @Test
  fun theBudgetIsWhatWasAgreed() {
    assertTrue("$threadCap messages", threadCap in 5..15)
    assertTrue("$bodyCap bytes", bodyCap in (256 * 1024)..(4 * 1024 * 1024))
  }

  @Test
  fun emptyThreadOmitsNothing() {
    val dto = FeedbackItemDtoMapper.toDto(feedback())
    assertTrue(dto.thread.isEmpty())
    assertEquals(0, dto.threadOmitted)
  }

  @Test
  fun aThreadAtTheCapIsKeptWhole() {
    val thread = (1..threadCap).map { message(it) }
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = thread))

    assertEquals(thread.map { it.id }, dto.thread.map { it.id })
    assertEquals(0, dto.threadOmitted)
  }

  /** Newest, not oldest: the note holds the original ask, so the thread's actionable end is the
   *  recent one - the latest question, the latest summary. */
  @Test
  fun anOverlongThreadKeepsTheNewestMessagesInOrder() {
    val thread = (1..threadCap + 7).map { message(it) }
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = thread))

    assertEquals(threadCap, dto.thread.size)
    assertEquals(thread.takeLast(threadCap).map { it.id }, dto.thread.map { it.id })
  }

  @Test
  fun omittedCountsWhatWasDropped() {
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = (1..threadCap + 7).map { message(it) }))
    assertEquals(7, dto.threadOmitted)
  }

  /** A cut body says so itself: threadOmitted counts messages and is zero here, so it cannot
   *  carry this. */
  @Test
  fun anOverlongBodyIsCutAndSaysSo() {
    val body = "x".repeat(bodyCap + 500)
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = listOf(message(1, body))))
    val cut = dto.thread.single().body

    assertEquals(0, dto.threadOmitted)
    assertTrue(cut.length < body.length)
    assertTrue(cut, cut.endsWith("[truncated, 500 more characters]"))
    assertTrue(cut.startsWith(body.take(bodyCap)))
  }

  @Test
  fun aBodyUnderTheCapIsVerbatim() {
    val body = "a short answer"
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = listOf(message(1, body))))

    assertEquals(body, dto.thread.single().body)
    assertFalse(dto.thread.single().body.contains("truncated"))
  }

  /** The note is the original ask and a separate field. Capping it would remove the one thing the
   *  agent was asked to act on. */
  @Test
  fun theNoteIsNeverTruncated() {
    val note = "n".repeat(200_000)
    val dto = FeedbackItemDtoMapper.toDto(
      feedback(note = note, thread = (1..threadCap + 5).map { message(it, "y".repeat(50_000)) }),
    )
    assertEquals(note, dto.note)
  }

  @Test
  fun theSnapshotCapIsUnchanged() {
    val snapshot = "z".repeat(9 * 1024)
    val dto = FeedbackItemDtoMapper.toDto(feedback(codeSnapshot = snapshot))

    assertEquals(8 * 1024, dto.codeSnapshot?.length)
    assertEquals(snapshot.take(8 * 1024), dto.codeSnapshot)
  }

  @Test
  fun aShortSnapshotIsVerbatim() {
    assertEquals("val x = 1", FeedbackItemDtoMapper.toDto(feedback()).codeSnapshot)
  }

  /** The hand check from the plan: 30 replies reach the agent bounded, newest kept, counted. */
  @Test
  fun thirtyRepliesArriveBoundedAndCounted() {
    val dto = FeedbackItemDtoMapper.toDto(feedback(thread = (1..30).map { message(it) }))

    assertEquals(threadCap, dto.thread.size)
    assertEquals(30 - threadCap, dto.threadOmitted)
    assertEquals("m30", dto.thread.last().id)
  }
}
