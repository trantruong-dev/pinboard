package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRibbonTest {

  private fun item(status: Status) = Feedback(
    id = "id-$status-${counter++}",
    status = status,
    scope = Scope.SELECTION,
    note = "note",
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 1,
    endLine = 2,
    codeSnapshot = "code",
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
    createdAt = 1,
    updatedAt = 1,
  )

  private var counter = 0

  @Test
  fun segmentsReadDoneThenInFlightThenOwed() {
    // Left to right the bar should fill up as work finishes, which only holds if the finished
    // segment is the leftmost one.
    assertEquals(listOf("Done", "Acknowledged", "Pending"), ProgressRibbon.segmentLabels())
  }

  @Test
  fun resolvedAndDismissedBothCountAsDone() {
    val counts = ProgressRibbon.segmentCounts(
      listOf(item(Status.RESOLVED), item(Status.DISMISSED), item(Status.PENDING)),
    )
    assertEquals(listOf(2, 0, 1), counts)
  }

  @Test
  fun everyItemLandsInExactlyOneSegment() {
    val items = Status.entries.map { item(it) }
    assertEquals(items.size, ProgressRibbon.segmentCounts(items).sum())
  }

  @Test
  fun anEmptyQueueHasNothingInAnySegment() {
    assertEquals(listOf(0, 0, 0), ProgressRibbon.segmentCounts(emptyList()))
  }
}
