package dev.pinboard.store

import dev.pinboard.model.Feedback
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/**
 * File-level persistence for one project's feedback store.
 *
 * Responsibilities:
 * - Resolve the JSON file path under [dev.pinboard.store.StorePaths.systemDir].
 * - Read (corrupt-safe: missing file -> empty, corrupt file -> backed up as `.corrupt` then empty,
 *   never crash the IDE).
 * - Atomic writes: write `.tmp` then `Files.move(ATOMIC_MOVE)`.
 * - Read-merge-write: before each flush, if the file's mtime changed since the last read,
 *   re-read from disk, merge by id via [FeedbackMerger], then write. This makes concurrent
 *   writers (two IDEs on the same repo) converge instead of silently overwriting each other.
 */
class FeedbackStoreFile(private val path: Path) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
  // Store the raw FileTime (nanosecond-precision on modern filesystems) instead of a truncated
  // millis value so two writes within the same millisecond are still detected as changed.
  private var lastKnownMtime: FileTime? = null
  private val tmpCounter = java.util.concurrent.atomic.AtomicLong(0)

  val exists: Boolean get() = Files.exists(path)

  fun readOrEmpty(): List<Feedback> {
    if (!Files.exists(path)) {
      lastKnownMtime = null
      return emptyList()
    }
    return try {
      val text = Files.readString(path)
      val parsed = json.decodeFromString(StoreFileContent.serializer(), text)
      lastKnownMtime = mtimeOf(path)
      parsed.items
    } catch (e: Exception) {
      // Corrupt or schema-unknown: back it up so data is recoverable, then start clean.
      backUpCorruptFile()
      lastKnownMtime = null
      emptyList()
    }
  }

  /**
   * Writes [memory] to disk. If the on-disk mtime changed since our last read (another process
   * wrote meanwhile), reads disk, merges, and writes the union. Returns the final persisted list.
   *
   * Synchronized so overlapping flush calls (e.g. a debounced flush racing dispose) serialize
   * their read-merge-write instead of interleaving.
   */
  @Synchronized
  fun flush(memory: List<Feedback>, tombstones: Set<String> = emptySet()): List<Feedback> {
    val diskHasChanged = mtimeOf(path) != lastKnownMtime
    val merged = if (diskHasChanged) {
      FeedbackMerger.merge(readOrEmpty(), memory, tombstones)
    } else {
      FeedbackMerger.merge(emptyList(), memory, tombstones)
    }
    writeAtomic(merged)
    lastKnownMtime = mtimeOf(path)
    return merged
  }

  private fun writeAtomic(items: List<Feedback>) {
    Files.createDirectories(path.parent)
    // Unique tmp name per writer (pid + counter) so two IDE processes flushing the same store
    // never write the same tmp path. A shared tmp name lets process B move A's torn tmp into
    // place, corrupting the store.
    val tmp = path.resolveSibling("${path.fileName}.${ProcessHandle.current().pid()}.${tmpCounter.incrementAndGet()}.tmp")
    val payload = json.encodeToString(StoreFileContent.serializer(), StoreFileContent(items = items))
    try {
      Files.writeString(tmp, payload)
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  private fun backUpCorruptFile() {
    try {
      Files.move(path, path.resolveSibling(path.fileName.toString() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING)
    } catch (_: IOException) {
      // Best-effort backup; if it fails the corrupt file simply stays and next read retries.
    }
  }

  private fun mtimeOf(p: Path): FileTime? = try {
    if (Files.exists(p)) Files.getLastModifiedTime(p) else null
  } catch (_: IOException) {
    null
  }
}

@Serializable
data class StoreFileContent(
  val version: Int = 1,
  val projectPath: String = "",
  val items: List<Feedback> = emptyList(),
)