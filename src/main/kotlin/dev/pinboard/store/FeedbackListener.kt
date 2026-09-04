package dev.pinboard.store

import com.intellij.util.messages.Topic

/**
 * Published whenever the store mutates in memory. The ToolWindow (phase 04) subscribes via the
 * project message bus to rebuild without polling. Agent status changes through MCP tools flow
 * through the same store, so the panel updates in real time.
 */
interface FeedbackListener {
  fun onChanged()

  companion object {
    @JvmField
    val TOPIC: Topic<FeedbackListener> =
      Topic.create("dev.pinboard.store.changed", FeedbackListener::class.java)
  }
}