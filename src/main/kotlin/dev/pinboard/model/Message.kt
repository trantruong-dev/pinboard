package dev.pinboard.model

import kotlinx.serialization.Serializable

enum class Author { HUMAN, AGENT }

@Serializable
data class Message(
  val id: String,
  val author: Author,
  val body: String,
  val createdAt: Long,
)