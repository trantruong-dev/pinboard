package dev.pinboard.mcp

import com.intellij.mcpserver.McpExpectedError
import kotlinx.serialization.Serializable

/**
 * Shared helpers for building tool responses and controlled errors.
 *
 * Tool methods return a `@Serializable` type, a String, or Unit; the platform serializes them
 * into the MCP text content. For expected business errors (missing id, invalid args) we throw
 * [McpExpectedError] so the agent sees a clean message instead of a stack trace.
 *
 * `McpExpectedError`'s constructor changed across 2025.2 builds (`(String)` -> `(String, JsonObject)`),
 * so [error] builds it reflectively with a plain-exception fallback. One artifact works on all builds.
 */
object ToolResponses {

  fun error(message: String): Nothing = throw buildExpectedError(message)

  private fun buildExpectedError(message: String): RuntimeException {
    // McpExpectedError constructor changed across 2025.2 builds:
    //   old: (String)            - 252.23892
    //   new: (String, JsonObject) - 252.28539 (default-arg ctor still exposed for JsonObject)
    // Try a 1-arg constructor, then a 2-arg with an empty JsonObject, then fall back to a plain
    // exception. All three surface as a clean agent-visible error; the McpExpectedError path just
    // avoids logging a stack trace on the newer build.
    val klass = McpExpectedError::class.java
    try {
      val oneArg = klass.constructors.firstOrNull { it.parameterCount == 1 }
      if (oneArg != null) return oneArg.newInstance(message) as RuntimeException
      val twoArg = klass.constructors.firstOrNull {
        it.parameterCount == 2 && it.parameterTypes[1] == kotlinx.serialization.json.JsonObject::class.java
      }
      if (twoArg != null) {
        return twoArg.newInstance(message, kotlinx.serialization.json.JsonObject(emptyMap())) as RuntimeException
      }
    } catch (_: Throwable) {
      // fall through
    }
    return RuntimeException(message)
  }
}

@Serializable
data class BatchResult(val affected: Int)

@Serializable
data class ClearResult(val deleted: Int)

@Serializable
data class ListResult(
  val items: List<FeedbackItemDto>,
  val totalPending: Int,
)