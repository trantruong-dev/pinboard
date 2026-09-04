package dev.pinboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UlidTest {

  @Test
  fun generates26CrockfordChars() {
    val id = Ulid.generate()
    assertEquals(26, id.length)
    assertTrue("all chars in Crockford alphabet", id.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
  }

  @Test
  fun monotonicByTime() {
    val first = Ulid.generate()
    Thread.sleep(2)
    val second = Ulid.generate()
    assertTrue("later ULID sorts greater", second > first)
  }

  @Test
  fun uniqueAcrossMany() {
    val ids = (1..1000).map { Ulid.generate() }.toSet()
    assertEquals(1000, ids.size)
  }
}