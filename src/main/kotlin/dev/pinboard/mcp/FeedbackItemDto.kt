package dev.pinboard.mcp

import dev.pinboard.model.Feedback
import dev.pinboard.model.Message
import kotlinx.serialization.Serializable

/**
 * Wire shape returned to the agent. Deliberately decoupled from the internal [Feedback] model so
 * the MCP contract can evolve without touching the store, and vice versa.
 */
@Serializable
data class FeedbackItemDto(
  val id: String,
  /** PENDING / ACKNOWLEDGED / RESOLVED / DISMISSED. Always emitted: the default list mixes
   *  PENDING with ACKNOWLEDGED, so without this the agent cannot tell which items it has already
   *  seen after a restart. */
  val status: String,
  val note: String,
  val scope: String,
  val filePath: String?,
  val startLine: Int?,
  val endLine: Int?,
  val language: String?,
  val codeSnapshot: String?,
  val stale: Boolean = false,
  val fileMissing: Boolean = false,
  val symbolPath: String?,
  val vcsRevision: String?,
  val thread: List<MessageDto>,
  /** How many of the oldest thread messages were dropped to bound the thread. Emitted because a
   *  history shortened in silence is one the agent would reason from without knowing it is partial
   *  - the human still has the whole thread in the panel. Zero means nothing was lost, and says
   *  nothing about a body cut inside a message, which is marked in that body instead. */
  val threadOmitted: Int = 0,
)

@Serializable
data class MessageDto(
  val id: String,
  val author: String,
  val body: String,
  val createdAt: Long,
)

/**
 * Maps an internal [Feedback] to the agent-facing DTO. The agent-visible snapshot is capped at
 * 8KB to avoid flooding the agent context (the store keeps up to 64KB).
 *
 * The thread is capped for the same reason and by the same rule: `feedback_list` returns up to 20
 * items at a time, and three tools (`feedback_reply`, `feedback_resolve`, `feedback_dismiss`) write
 * into threads, so an agent that replies repeatedly would otherwise grow its own context with no
 * bound on it. Two caps, because either alone can be defeated by the other: many small messages,
 * or one enormous one.
 *
 * This bounds what the *agent* wrote, not the whole item. [Feedback.note] stays whole however long
 * it is - it is the human's ask, the one thing the agent was called here to act on, and trimming it
 * would cut the instruction rather than the chatter. So an item is not bounded; its thread is.
 */
object FeedbackItemDtoMapper {

  private const val AGENT_SNAPSHOT_CAP = 8 * 1024

  /** Newest messages kept. [Feedback.note] is the original ask and is a separate, uncapped field,
   *  so everything in the thread is what came after - the actionable end is the recent one. Ten is
   *  already a thread that has gone round more times than a working exchange does. */
  private const val MAX_THREAD_MESSAGES = 10

  /**
   * Per-message body cap.
   *
   * 1MB, which is not a context budget - it is a backstop. A stack trace, a diff or a wall of
   * compiler errors is ordinary thread content and runs to tens of kilobytes, so a cap tight enough
   * to be a budget would cut legitimate messages every day and leave the agent reading half a trace.
   * The message count is what does the bounding; this only stops one pathological message, a dumped
   * file or a runaway log, from being the whole payload.
   */
  private const val MAX_MESSAGE_BODY = 1024 * 1024

  fun toDto(feedback: Feedback, stale: Boolean = false, fileMissing: Boolean = false): FeedbackItemDto {
    val snapshot = feedback.codeSnapshot?.let { capSnapshot(it) }
    val omitted = (feedback.thread.size - MAX_THREAD_MESSAGES).coerceAtLeast(0)
    return FeedbackItemDto(
      id = feedback.id,
      status = feedback.status.name,
      note = feedback.note,
      scope = feedback.scope.name,
      filePath = feedback.filePath,
      startLine = feedback.startLine,
      endLine = feedback.endLine,
      language = feedback.language,
      codeSnapshot = snapshot,
      stale = stale,
      fileMissing = fileMissing,
      symbolPath = feedback.symbolPath,
      vcsRevision = feedback.vcsRevision,
      thread = feedback.thread.takeLast(MAX_THREAD_MESSAGES).map { toMessageDto(it) },
      threadOmitted = omitted,
    )
  }

  private fun toMessageDto(message: Message): MessageDto {
    return MessageDto(
      id = message.id,
      author = message.author.name,
      body = capBody(message.body),
      createdAt = message.createdAt,
    )
  }

  /**
   * A truncated body says so in the body itself rather than relying on `threadOmitted`, which
   * counts dropped messages and can be zero while a body was cut. The marker is inside the text the
   * agent reads, where the loss actually happened.
   */
  private fun capBody(body: String): String {
    if (body.length <= MAX_MESSAGE_BODY) return body
    val dropped = body.length - MAX_MESSAGE_BODY
    return body.take(MAX_MESSAGE_BODY) + "\n[truncated, $dropped more characters]"
  }

  private fun capSnapshot(snapshot: String): String {
    return if (snapshot.length <= AGENT_SNAPSHOT_CAP) snapshot else snapshot.take(AGENT_SNAPSHOT_CAP)
  }
}
