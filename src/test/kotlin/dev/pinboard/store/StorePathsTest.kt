package dev.pinboard.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class StorePathsTest : BasePlatformTestCase() {

  fun testStoreFileResolvesUnderPinboardDir() {
    val file = StorePaths.storeFile(project)
    assertNotNull("storeFile requires a project base path", file)
    val dir = file!!.parent
    assertEquals("pinboard", dir.fileName.toString())
    assertTrue("filename is 16 hex chars + .json", file.fileName.toString().matches(Regex("^[0-9a-f]{16}\\.json$")))
  }

  fun testSameProjectBasePathStableAcrossCalls() {
    val a = StorePaths.storeFile(project)!!
    val b = StorePaths.storeFile(project)!!
    assertEquals(a, b)
  }

  fun testStoreIsOutsideVcsAndIdeaDir() {
    val path = StorePaths.storeFile(project)!!
    val text = path.toString()
    assertTrue("must live under system path, not project/.idea", !text.contains(project.basePath!!))
  }

  fun testIdentityIgnoresSeparatorAndTrailingSlash() {
    // The queue is keyed by a hash of this string, so a path spelled differently would point at a
    // different, empty file and read exactly like lost feedback.
    val canonical = "C:/work/repo"
    assertEquals(canonical, StorePaths.identity("C:/work/repo"))
    assertEquals(canonical, StorePaths.identity("C:/work/repo/"))
    assertEquals(canonical, StorePaths.identity("C:" + '\\' + "work" + '\\' + "repo"))
    assertEquals(canonical, StorePaths.identity("C:" + '\\' + "work" + '\\' + "repo" + '\\'))
  }

  fun testIdentityKeepsDifferentProjectsApart() {
    assertFalse(
      "two projects must never share a queue",
      StorePaths.identity("C:/work/repo-a") == StorePaths.identity("C:/work/repo-b"),
    )
  }

  fun testIdentityPreservesCase() {
    // Paths are case-sensitive on Linux; folding case would merge two real projects there.
    assertFalse(StorePaths.identity("/work/Repo") == StorePaths.identity("/work/repo"))
  }
}
