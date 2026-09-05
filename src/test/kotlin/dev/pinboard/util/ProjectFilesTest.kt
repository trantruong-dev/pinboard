package dev.pinboard.util

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class ProjectFilesTest : BasePlatformTestCase() {

  private fun createOnDiskFile(relPath: String): Path {
    val path = Path.of(project.basePath!!).resolve(relPath)
    Files.createDirectories(path.parent)
    Files.writeString(path, "content")
    LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())
    return path
  }

  /**
   * The tab colour provider walks the queue with one file in hand, so it needs the reverse of
   * [ProjectFiles.resolve] to produce exactly the string shape the store holds - otherwise every
   * comparison silently misses and no tab is ever tinted.
   */
  fun testRelativePathRoundTripsWithResolve() {
    createOnDiskFile("src/deep/Nested.kt")
    val file = ProjectFiles.resolve(project, "src/deep/Nested.kt")!!

    assertEquals("src/deep/Nested.kt", ProjectFiles.relativePath(project, file))
  }

  fun testRelativePathUsesForwardSlashesLikeTheStore() {
    createOnDiskFile("src/deep/Nested.kt")
    val file = ProjectFiles.resolve(project, "src/deep/Nested.kt")!!

    val relative = ProjectFiles.relativePath(project, file)!!
    assertFalse("stored paths never contain backslashes", relative.contains('\\'))
    assertEquals(FilePaths.canonical(relative), relative)
  }

  fun testFileOutsideTheProjectHasNoRelativePath() {
    val outside = Files.createTempFile("pinboard-outside", ".kt")
    try {
      val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(outside.toString())!!
      assertNull(ProjectFiles.relativePath(project, file))
    } finally {
      Files.deleteIfExists(outside)
    }
  }

  /** The root itself is not a file anyone can pin, and an empty relative path matches nothing. */
  fun testProjectRootItselfHasNoRelativePath() {
    val root = ProjectFiles.projectRoot(project)!!
    assertNull(ProjectFiles.relativePath(project, root))
  }

  /**
   * A sibling directory whose name merely starts with the project's is not inside the project.
   *
   * A plain prefix test accepts `/w/proj2/src/Foo.kt` against root `/w/proj` and hands back
   * `2/src/Foo.kt`, which would then be compared against stored paths as if the file were ours.
   */
  fun testASiblingDirectoryWithASharedPrefixIsOutsideTheProject() {
    val root = Path.of(project.basePath!!)
    val sibling = root.resolveSibling(root.fileName.toString() + "2")
    val intruder = sibling.resolve("src/Foo.kt")
    Files.createDirectories(intruder.parent)
    Files.writeString(intruder, "content")
    try {
      val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(intruder.toString())!!
      assertNull(ProjectFiles.relativePath(project, file))
    } finally {
      Files.deleteIfExists(intruder)
      Files.deleteIfExists(intruder.parent)
      Files.deleteIfExists(sibling)
    }
  }
}
