package dev.pinboard.model

import kotlinx.serialization.Serializable

enum class Status { PENDING, ACKNOWLEDGED, RESOLVED, DISMISSED }

enum class Scope { SELECTION, FILE, PROJECT }

@Serializable
data class Feedback(
  val id: String,
  val status: Status,
  val scope: Scope,
  val note: String,
  val filePath: String?,
  val language: String?,
  val startLine: Int?,
  val endLine: Int?,
  val codeSnapshot: String?,
  val contentSha256: String?,
  val truncated: Boolean = false,
  val symbolPath: String?,
  val vcsRevision: String?,
  val thread: List<Message> = emptyList(),
  val createdAt: Long,
  val updatedAt: Long,
) {
  fun copyWithStatus(newStatus: Status): Feedback = copy(status = newStatus, updatedAt = System.currentTimeMillis())

  fun copyWithMessage(newMessage: Message): Feedback =
    copy(thread = thread + newMessage, updatedAt = System.currentTimeMillis())
}