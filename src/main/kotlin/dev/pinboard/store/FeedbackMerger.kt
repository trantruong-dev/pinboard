package dev.pinboard.store

import dev.pinboard.model.Feedback
import dev.pinboard.model.Message

/**
 * Merges two feedback lists by id. Same id -> the one with newer [Feedback.updatedAt] wins, and a
 * tie goes to [merge]'s memory side (memory is the live state, disk is a snapshot of it).
 * Ids present in only one side are kept. Ids in [tombstones] are dropped from the disk side
 * so locally-deleted items never resurrect.
 *
 * Whole-record last-write-wins is wrong for [Feedback.thread]: threads are append-only and two
 * processes sharing one store (see [StorePaths]) append independently, so the losing record's
 * messages are unioned into the winner rather than discarded.
 *
 * Known limitation, accepted deliberately: every other field still resolves last-write-wins for
 * the whole record. Edit the same item's note from two IDE windows at once and one edit is lost.
 * Fixing that needs per-field timestamps or a CRDT, which is a steep price for a notes queue where
 * concurrent edits to one item are rare. Documented for users in README, "Known limitations".
 */
object FeedbackMerger {

  fun merge(disk: List<Feedback>, memory: List<Feedback>, tombstones: Set<String> = emptySet()): List<Feedback> {
    val byId = LinkedHashMap<String, Feedback>(disk.size + memory.size)

    for (item in disk) {
      if (item.id !in tombstones) {
        byId[item.id] = item
      }
    }

    for (item in memory) {
      val existing = byId[item.id]
      byId[item.id] = when {
        existing == null -> item
        item.updatedAt >= existing.updatedAt -> item.withThreadUnion(existing)
        else -> existing.withThreadUnion(item)
      }
    }

    return byId.values.sortedBy { it.createdAt }
  }

  /**
   * Returns this record with [other]'s thread messages folded in, keyed by [Message.id] so a
   * message present on both sides is not duplicated. Returns the receiver untouched when [other]
   * contributes nothing, which keeps an existing thread's order stable.
   */
  private fun Feedback.withThreadUnion(other: Feedback): Feedback {
    if (other.thread.isEmpty()) return this
    val byMessageId = LinkedHashMap<String, Message>(thread.size + other.thread.size)
    for (message in thread) byMessageId[message.id] = message
    for (message in other.thread) byMessageId.putIfAbsent(message.id, message)
    if (byMessageId.size == thread.size) return this
    return copy(thread = byMessageId.values.sortedBy { it.createdAt })
  }
}
