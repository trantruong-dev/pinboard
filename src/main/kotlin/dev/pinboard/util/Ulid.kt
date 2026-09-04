package dev.pinboard.util

import java.util.concurrent.ThreadLocalRandom

/**
 * Standard ULID generator: 26 Crockford base32 chars, big-endian. The first 10 chars encode the
 * 48-bit millisecond timestamp (3 significant bits in the first char, then 5 bits each), the
 * remaining 16 chars encode 80 bits of randomness. Lexicographic order == chronological order.
 */
object Ulid {
  private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  private val random = ThreadLocalRandom.current()

  fun generate(): String {
    val timestamp = System.currentTimeMillis()
    val randBytes = ByteArray(10) // 80 bits
    random.nextBytes(randBytes)

    val sb = StringBuilder(26)
    // Timestamp: 48 bits, most significant first. First char holds bits 47..45 (3 bits).
    var t = timestamp
    val timeChars = CharArray(10)
    for (i in 9 downTo 0) {
      timeChars[i] = CROCKFORD[(t and 0x1F).toInt()]
      t = t ushr 5
    }
    sb.append(timeChars)

    // Randomness: 80 bits as 10 bytes -> 16 chars of 5 bits each, big-endian.
    var acc = 0
    var bits = 0
    for (b in randBytes) {
      acc = (acc shl 8) or (b.toInt() and 0xFF)
      bits += 8
      while (bits >= 5) {
        bits -= 5
        sb.append(CROCKFORD[(acc ushr bits) and 0x1F])
      }
      acc = acc and ((1 shl bits) - 1) // keep only the leftover low bits
    }
    return sb.toString()
  }
}