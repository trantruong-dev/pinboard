package dev.pinboard.ui.toolwindow

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
 * Pure JUnit, no fixture: [FeedbackMarkdown] must stay free of platform services, and a test that
 * needed an IDE to run would stop enforcing that.
 */
class FeedbackMarkdownTest {

  private fun feedback(
    scope: Scope = Scope.SELECTION,
    note: String = "note",
    filePath: String? = "src/Foo.kt",
    language: String? = "Kotlin",
    startLine: Int? = 12,
    endLine: Int? = 20,
    codeSnapshot: String? = "val x = 1",
    thread: List<Message> = emptyList(),
  ) = Feedback(
    id = "id",
    status = Status.PENDING,
    scope = scope,
    note = note,
    filePath = filePath,
    language = language,
    startLine = startLine,
    endLine = endLine,
    codeSnapshot = codeSnapshot,
    contentSha256 = "sha",
    symbolPath = null,
    vcsRevision = null,
    thread = thread,
    createdAt = 1_757_260_800_000,
    updatedAt = 1_757_260_800_000,
  )

  private fun render(
    feedback: Feedback = feedback(),
    stale: Boolean = false,
    fileMissing: Boolean = false,
  ) = FeedbackMarkdown.render(FeedbackItemNode(feedback, stale, fileMissing))

  private fun message(author: Author, body: String) =
    Message(id = "m-$body", author = author, body = body, createdAt = 1_757_260_860_000)

  @Test
  fun rangeHeaderCarriesBothLines() {
    assertTrue(render().startsWith("### src/Foo.kt:12-20 - Pending\n"))
  }

  @Test
  fun singleLineHeaderDropsTheRange() {
    val text = render(feedback(endLine = 12))
    assertTrue(text.startsWith("### src/Foo.kt:12 - Pending\n"))
  }

  @Test
  fun noStartLineLeavesTheBarePath() {
    val text = render(feedback(startLine = null, endLine = null))
    assertTrue(text.startsWith("### src/Foo.kt - Pending\n"))
  }

  @Test
  fun missingPathReadsAsUnknownLocation() {
    val text = render(feedback(filePath = null))
    assertTrue(text.startsWith("### Unknown location - Pending\n"))
  }

  /** A project-scope pin has no lines, so a snapshot on one is not the pinned code. */
  @Test
  fun projectScopeHasNoCodeBlock() {
    val text = render(feedback(scope = Scope.PROJECT, filePath = null, codeSnapshot = "val x = 1"))
    assertTrue(text.startsWith("### Whole project - Pending\n"))
    assertFalse(text.contains("Code at capture time"))
  }

  @Test
  fun noSnapshotOmitsTheCodeSection() {
    assertFalse(render(feedback(codeSnapshot = null)).contains("Code at capture time"))
  }

  @Test
  fun emptyThreadOmitsTheConversationSection() {
    assertFalse(render().contains("Conversation"))
  }

  @Test
  fun conversationKeepsAuthorsAndOrder() {
    val text = render(
      feedback(
        thread = listOf(
          message(Author.HUMAN, "please fix"),
          message(Author.AGENT, "done"),
        ),
      ),
    )
    val you = text.indexOf("- You (")
    val agent = text.indexOf("- Agent (")
    assertTrue(you > 0)
    assertTrue(agent > you)
    assertTrue(text.contains("please fix"))
    assertTrue(text.contains("done"))
  }

  @Test
  fun staleFlagShowsOnTheMetaLine() {
    assertTrue(render(stale = true).contains(" - stale: code changed since pinned"))
  }

  /** A missing file is the stronger statement, and saying both would only muddy it. */
  @Test
  fun missingFileOutranksStale() {
    val text = render(stale = true, fileMissing = true)
    assertTrue(text.contains(" - file no longer exists"))
    assertFalse(text.contains("stale: code changed since pinned"))
  }

  @Test
  fun snapshotContainingAFenceGetsALongerOne() {
    val snapshot = "before\n```\ninner\n```\nafter"
    val text = render(feedback(codeSnapshot = snapshot))
    assertTrue(text.contains("\n````kotlin\n$snapshot\n````"))
  }

  @Test
  fun snapshotWithFiveBackticksGetsSix() {
    val snapshot = "`````"
    val text = render(feedback(codeSnapshot = snapshot))
    assertTrue(text.contains("\n``````kotlin\n$snapshot\n``````"))
  }

  @Test
  fun unknownLanguageLeavesTheFenceBare() {
    val text = render(feedback(language = null, codeSnapshot = "x"))
    assertTrue(text.contains("\n```\nx\n```"))
  }

  /** The store keeps text raw and Markdown is the output format, so there is nothing to escape. */
  @Test
  fun noteIsVerbatim() {
    val note = "<script> & \"quotes\" & 'apostrophes'"
    assertTrue(render(feedback(note = note)).contains(note))
  }

  @Test
  fun multiLineNoteKeepsItsLineBreaks() {
    val text = render(feedback(note = "first\nsecond"))
    assertTrue(text.contains("**Note**\nfirst\nsecond"))
    assertFalse(text.contains("<br/>"))
  }

  /** Asserted by shape, not by instant: the formatter uses the default zone and CI is not local. */
  @Test
  fun metaLineCarriesAFormattedTimestamp() {
    val metaLine = render().lines()[1]
    assertTrue(metaLine, Regex("""^Pinned \d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").matches(metaLine))
  }

  /** The whole shape in one place, so a change to any separator has to be a deliberate one. */
  @Test
  fun rendersTheWholeItem() {
    val text = render(
      feedback(thread = listOf(message(Author.AGENT, "on it"))),
      stale = true,
    )
    // Timestamps render in the default zone, and CI does not run in the author's.
    val normalized = text.replace(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}"""), "<time>")
    assertEquals(
      """
      ### src/Foo.kt:12-20 - Pending
      Pinned <time> - stale: code changed since pinned

      **Note**
      note

      **Code at capture time**
      ```kotlin
      val x = 1
      ```

      **Conversation**
      - Agent (<time>): on it
      """.trimIndent(),
      normalized,
    )
  }
}
