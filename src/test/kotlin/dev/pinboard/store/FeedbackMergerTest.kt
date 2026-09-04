package dev.pinboard.store

import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackMergerTest {

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
    codeSnapshot = "code",
    contentSha256 = "sha",
    symbolPath = "sym",
    vcsRevision = null,
    thread = emptyList(),
    createdAt = 1,
    updatedAt = updatedAt,
  )

  @Test
  fun unionOfBothSides() {
    val merged = FeedbackMerger.merge(
      disk = listOf(item("a", Status.PENDING, 1)),
      memory = listOf(item("b", Status.PENDING, 1)),
    )
    assertEquals(setOf("a", "b"), merged.map { it.id }.toSet())
  }

  @Test
  fun newerUpdatedAtWins() {
    val merged = FeedbackMerger.merge(
      disk = listOf(item("a", Status.PENDING, 1)),
      memory = listOf(item("a", Status.RESOLVED, 2)),
    )
    assertEquals(1, merged.size)
    assertEquals(Status.RESOLVED, merged.single().status)
  }

  @Test
  fun tombstoneDropsFromDiskSide() {
    val merged = FeedbackMerger.merge(
      disk = listOf(item("a", Status.PENDING, 1), item("b", Status.PENDING, 1)),
      memory = emptyList(),
      tombstones = setOf("a"),
    )
    assertEquals(listOf("b"), merged.map { it.id })
  }

  @Test
  fun tombstoneOnlyDropsDiskSideMemoryWins() {
    val merged = FeedbackMerger.merge(
      disk = listOf(item("a", Status.PENDING, 1)),
      memory = listOf(item("a", Status.RESOLVED, 2)),
      tombstones = setOf("a"),
    )
    assertEquals(1, merged.size)
    assertEquals(Status.RESOLVED, merged.single().status)
  }

  @Test
  fun sortedByCreatedAt() {
    val merged = FeedbackMerger.merge(
      disk = listOf(item("older", Status.PENDING, 1).copy(createdAt = 1)),
      memory = listOf(item("newer", Status.PENDING, 1).copy(createdAt = 5)),
    )
    assertEquals(listOf("older", "newer"), merged.map { it.id })
  }

  @Test
  fun threadMessagesFromLosingSideSurvive() {
    val m1 = Message(id = "m1", author = Author.HUMAN, body = "note", createdAt = 10)
    val m2 = Message(id = "m2", author = Author.HUMAN, body = "human reply", createdAt = 20)
    val m3 = Message(id = "m3", author = Author.AGENT, body = "agent reply", createdAt = 30)
    val merged = FeedbackMerger.merge(
      disk = listOf(item("a", Status.PENDING, 100).copy(thread = listOf(m1, m2))),
      memory = listOf(item("a", Status.PENDING, 105).copy(thread = listOf(m1, m3))),
    )
    assertEquals(listOf("m1", "m2", "m3"), merged.single().thread.map { it.id })
  }
}
