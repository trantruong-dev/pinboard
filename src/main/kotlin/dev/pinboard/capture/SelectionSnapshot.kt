package dev.pinboard.capture

/**
 * Immutable result of capturing the current editor selection for a feedback item.
 * For scope FILE/PROJECT these are the same fields minus the selection-specific ones (null).
 */
data class SelectionSnapshot(
  val filePath: String?,
  val language: String?,
  val startLine: Int?,
  val endLine: Int?,
  val codeSnapshot: String?,
  val contentSha256: String?,
  val truncated: Boolean = false,
  val symbolPath: String?,
  val vcsRevision: String?,
)