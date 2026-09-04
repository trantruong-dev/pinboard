package dev.pinboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FilePathsTest {

  @Test
  fun testCanonicalRewritesWindowsSeparators() {
    assertEquals("src/main/kotlin/Foo.kt", FilePaths.canonical("src" + '\\' + "main" + '\\' + "kotlin" + '\\' + "Foo.kt"))
  }

  @Test
  fun testCanonicalLeavesForwardSlashesAlone() {
    assertEquals("src/main/Foo.kt", FilePaths.canonical("src/main/Foo.kt"))
    assertEquals("Foo.kt", FilePaths.canonical("Foo.kt"))
  }

  @Test
  fun testFileNameAcceptsEitherSeparator() {
    assertEquals("Foo.kt", FilePaths.fileName("src/main/Foo.kt"))
    assertEquals("Foo.kt", FilePaths.fileName("src" + '\\' + "main" + '\\' + "Foo.kt"))
    assertEquals("Foo.kt", FilePaths.fileName("Foo.kt"))
  }
}
