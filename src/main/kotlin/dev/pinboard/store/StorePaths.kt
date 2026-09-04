package dev.pinboard.store

import com.intellij.openapi.project.Project
import dev.pinboard.util.FilePaths
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Resolves the store file path for a project.
 *
 * Location: `PathManager.getSystemPath()/pinboard/<sha256(projectBasePath).take(16)>.json`
 *
 * The path is derived from the absolute project base path so two IDE processes opening the same
 * repo share one queue, while different repos never collide. Located outside VCS and outside
 * `.idea/` by construction.
 */
object StorePaths {

  fun storeFile(project: Project): Path? {
    val basePath = project.basePath ?: return null
    val dir = systemDir()
    val hash = sha256(identity(basePath)).take(16)
    return dir.resolve("$hash.json")
  }

  /**
   * The string a project's queue is keyed by.
   *
   * The identity is a hash of the base path, so any difference in how the path is spelled - a
   * trailing slash, a Windows separator - would silently point at a different, empty file and look
   * exactly like lost feedback. Normalising here means the same project keeps its queue however
   * the IDE happens to report the path.
   *
   * Case is deliberately left alone: paths are case-sensitive on Linux, and folding it would merge
   * two genuinely different projects there.
   */
  internal fun identity(basePath: String): String =
    FilePaths.canonical(basePath).trimEnd('/')

  fun systemDir(): Path {
    val systemPath = com.intellij.openapi.application.PathManager.getSystemPath()
    return Path.of(systemPath).resolve("pinboard")
  }

  private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}