package dev.pinboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256Test {

  @Test
  fun stableAcrossCalls() {
    val text = "fun foo() { return 42 }"
    assertEquals(Sha256.hex(text), Sha256.hex(text))
  }

  @Test
  fun knownVector() {
    // SHA-256 of empty string
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Sha256.hex("")
    )
  }

  @Test
  fun differentInputsDifferentHashes() {
    val a = Sha256.hex("abc")
    val b = Sha256.hex("abd")
    assertEquals(64, a.length)
    assertEquals(64, b.length)
    assert(a != b)
  }

  /**
   * The capture/detection contract: a whole-line selection carries a trailing separator, the
   * line-based re-derivation never does. Both must canonicalize to the same string or every
   * whole-line pin would be reported stale against an unchanged file.
   */
  @Test
  fun normalizeStripsTrailingNewlineSoWholeLineSelectionsMatch() {
    assertEquals(
      Sha256.normalizeForComparison("fun i2() {}"),
      Sha256.normalizeForComparison("fun i2() {}\n"),
    )
  }

  @Test
  fun normalizeFoldsCrlfAndLoneCr() {
    assertEquals("a\nb", Sha256.normalizeForComparison("a\r\nb"))
    assertEquals("a\nb", Sha256.normalizeForComparison("a\rb"))
    assertEquals("a\nb", Sha256.normalizeForComparison("a\r\nb\r\n"))
  }

  @Test
  fun normalizeKeepsInteriorBlankLines() {
    // Only trailing separators are dropped; blank lines inside the selection are real content.
    assertEquals("a\n\nb", Sha256.normalizeForComparison("a\n\nb\n"))
  }

  @Test
  fun normalizeIsIdempotent() {
    val once = Sha256.normalizeForComparison("a\r\nb\r\n")
    assertEquals(once, Sha256.normalizeForComparison(once))
  }
}