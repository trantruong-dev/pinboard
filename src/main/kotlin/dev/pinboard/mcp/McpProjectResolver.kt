package dev.pinboard.mcp

import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import kotlin.coroutines.CoroutineContext

/**
 * Resolves the current [Project] from an MCP tool's coroutine context.
 *
 * The `com.intellij.mcpServer` API renamed this accessor between 2025.2 builds:
 * - 2025.2 (252.23892): `com.intellij.mcpserver.ProjectContextElementKt.getProject(CoroutineContext)`
 * - 2025.2.6.3 (252.28539): `com.intellij.mcpserver.McpCallInfoKt.getProject(CoroutineContext)`
 *
 * Both expose the same static method shape. We look them up reflectively so one plugin artifact
 * runs on every 2025.2+ build without a compile-time dependency on the renamed class. This is the
 * single place that touches the drifting API - everything else in `mcp/` uses [currentProject].
 */
object McpProjectResolver {

  private val accessor: Method? = findAccessor()

  private fun findAccessor(): Method? {
    val candidates = listOf(
      "com.intellij.mcpserver.McpCallInfoKt",   // newer builds
      "com.intellij.mcpserver.ProjectContextElementKt", // original 2025.2
    )
    for (className in candidates) {
      try {
        val clazz = Class.forName(className)
        val method = clazz.getMethod("getProject", CoroutineContext::class.java)
        return method
      } catch (_: Throwable) {
        // try next
      }
    }
    return null
  }

  /** Returns the project for the current tool call, or null if the accessor is unavailable. */
  fun currentProject(context: CoroutineContext): Project? {
    val override = testProjectOverride
    if (override != null) return override
    val method = accessor ?: return null
    return try {
      method.invoke(null, context) as? Project
    } catch (_: Throwable) {
      null
    }
  }

  /** Test hook: lets platform tests inject a project when calling tools without an MCP context. */
  @Volatile
  var testProjectOverride: Project? = null
}