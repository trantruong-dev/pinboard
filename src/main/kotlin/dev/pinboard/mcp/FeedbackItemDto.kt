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
 */
object FeedbackItemDtoMapper {

  private const val AGENT_SNAPSHOT_CAP = 8 * 1024

  fun toDto(feedback: Feedback, stale: Boolean = false, fileMissing: Boolean = false): FeedbackItemDto {
    val snapshot = feedback.codeSnapshot?.let { capSnapshot(it) }
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
      thread = feedback.thread.map { toMessageDto(it) },
    )
  }

  private fun toMessageDto(message: Message): MessageDto {
    return MessageDto(
      id = message.id,
      author = message.author.name,
      body = message.body,
      createdAt = message.createdAt,
    )
  }

  private fun capSnapshot(snapshot: String): String {
    return if (snapshot.length <= AGENT_SNAPSHOT_CAP) snapshot else snapshot.take(AGENT_SNAPSHOT_CAP)
  }
}