package dev.pinboard.util

/**
 * The one canonical shape for the project-relative paths stored on feedback items: forward
 * slashes, on every platform.
 *
 * `FileUtil.getRelativePath` returns OS-native separators while `VirtualFile.findFileByRelativePath`
 * only understands `/`. Those two drifting apart made every file below the project root report as
 * missing on Windows, so the conversion lives in exactly one place now.
 */
object FilePaths {

  /** [path] with Windows separators rewritten to forward slashes. */
  fun canonical(path: String): String = path.replace('\\', '/')

  /** Last segment of [path], accepting either separator. */
  fun fileName(path: String): String = canonical(path).substringAfterLast('/')
}
