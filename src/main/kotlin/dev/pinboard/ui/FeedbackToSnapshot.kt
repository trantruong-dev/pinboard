package dev.pinboard.ui

import dev.pinboard.capture.SelectionSnapshot
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope

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

/**
 * Where a pin points, in one line, as it is shown to the user.
 *
 * Shared for the same reason as above: the detail panel's header and the Markdown a copied item
 * produces have to read identically, and two copies of this `when` would drift the first time one
 * of them was touched.
 */
internal fun Feedback.locationLabel(): String = when {
  scope == Scope.PROJECT -> "Whole project"
  filePath == null -> "Unknown location"
  startLine == null -> filePath
  endLine != null && endLine != startLine -> "$filePath:$startLine-$endLine"
  else -> "$filePath:$startLine"
}
