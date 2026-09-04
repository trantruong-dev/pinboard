package dev.pinboard.util

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Stable hex SHA-256 of a UTF-8 string. Used by capture and stale detection. */
object Sha256 {
  fun hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  /**
   * Canonicalizes selection text before hashing so capture and stale detection always compare the
   * same string.
   *
   * Capture stores exactly what the user selected, which for a whole-line selection (Shift+Down,
   * select-all) ends with a line separator. Stale detection re-derives the range line by line and
   * joins with "\n", which cannot produce that trailing separator. Without stripping it here, an
   * untouched file would be reported stale the moment the user selected whole lines.
   *
   * CRLF is folded to LF for the same reason: both sides must agree regardless of file endings.
   */
  fun normalizeForComparison(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
}