package dev.pinboard.ui

import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.model.Feedback

/**
 * Adapts a stored item back to the shape the capture UI renders.
 *
 * Shared rather than private to one panel because both places that show a stored item's code need
 * it: the detail panel's preview, and the dialog that reopens a pinned note for editing.
 */
internal fun Feedback.toSnapshot(): SelectionSnapshot = SelectionSnapshot(
  filePath = filePath,
  language = language,
  startLine = startLine,
  endLine = endLine,
  codeSnapshot = codeSnapshot,
  contentSha256 = contentSha256,
  truncated = truncated,
  symbolPath = symbolPath,
  vcsRevision = vcsRevision,
)
