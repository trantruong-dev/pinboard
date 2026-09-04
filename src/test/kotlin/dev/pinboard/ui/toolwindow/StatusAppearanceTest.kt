package dev.pinboard.ui.toolwindow

import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusAppearanceTest {

  private fun node(status: Status, stale: Boolean = false, fileMissing: Boolean = false) =
    FeedbackItemNode(
      feedback = Feedback(
        id = "id",
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
      ),
      stale = stale,
      fileMissing = fileMissing,
    )

  @Test
  fun onlyPendingAndAcknowledgedCountAsOpen() {
    assertTrue(StatusAppearance.isOpen(Status.PENDING))
    assertTrue(StatusAppearance.isOpen(Status.ACKNOWLEDGED))
    assertFalse(StatusAppearance.isOpen(Status.RESOLVED))
    assertFalse(StatusAppearance.isOpen(Status.DISMISSED))
  }

  @Test
  fun finishedStatusesShareOneColour() {
    // The ribbon merges RESOLVED and DISMISSED into a single "Done" segment, which only reads as
    // one segment if both paint the same.
    assertEquals(StatusAppearance.color(Status.RESOLVED), StatusAppearance.color(Status.DISMISSED))
  }

  @Test
  fun openStatusesEachHaveTheirOwnColour() {
    val colours = listOf(Status.PENDING, Status.ACKNOWLEDGED, Status.RESOLVED)
      .map { StatusAppearance.color(it) }
    assertEquals(colours.size, colours.distinct().size)
  }

  @Test
  fun aMissingFileOutranksMerelyStale() {
    assertEquals("file missing", StatusAppearance.warning(node(Status.PENDING, stale = true, fileMissing = true)))
    assertEquals("stale", StatusAppearance.warning(node(Status.PENDING, stale = true)))
    assertNull(StatusAppearance.warning(node(Status.PENDING)))
  }

  @Test
  fun finishedItemsNeverWarn() {
    // Code moving on after the agent fixed it is the expected outcome, not a defect. Warning here
    // would put a red flag on every completed item.
    for (status in listOf(Status.RESOLVED, Status.DISMISSED)) {
      assertNull(StatusAppearance.warning(node(status, stale = true, fileMissing = true)))
    }
  }

  @Test
  fun everyStatusHasALabel() {
    assertEquals(
      listOf("Pending", "Acknowledged", "Resolved", "Dismissed"),
      Status.entries.map { StatusAppearance.label(it) },
    )
  }
}
