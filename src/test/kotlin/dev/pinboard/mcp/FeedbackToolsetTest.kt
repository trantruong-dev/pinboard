package dev.pinboard.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.pinboard.model.Author
import dev.pinboard.model.Feedback
import dev.pinboard.model.Scope
import dev.pinboard.model.Status
import dev.pinboard.store.FeedbackStore
import dev.pinboard.util.Sha256
import dev.pinboard.util.Ulid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun JsonObjectBuilder.putString(key: String, value: String) = put(key, JsonPrimitive(value))
private fun JsonObjectBuilder.putInt(key: String, value: Int) = put(key, JsonPrimitive(value))

private fun idsArray(vararg ids: String) = buildJsonObject {
  putJsonArray("ids") {
    ids.forEach { add(JsonPrimitive(it)) }
  }
}

class FeedbackToolsetTest : BasePlatformTestCase() {

  private val tools: Map<String, com.intellij.mcpserver.McpTool> by lazy {
    ReflectionToolsProvider().getTools().associateBy { it.descriptor.name }
  }

  override fun setUp() {
    super.setUp()
    // Tools are invoked directly (no MCP coroutine context carrying the Project), so inject it.
    McpProjectResolver.testProjectOverride = project
  }

  override fun tearDown() {
    McpProjectResolver.testProjectOverride = null
    super.tearDown()
  }

  private fun item(id: String, status: Status = Status.PENDING) = Feedback(
    id = id,
    status = status,
    scope = Scope.SELECTION,
    note = "note-$id",
    filePath = "src/Foo.kt",
    language = "Kotlin",
    startLine = 1,
    endLine = 2,
    codeSnapshot = "fun foo() {}\nfun bar() {}\n",
    contentSha256 = Sha256.hex("fun foo() {}\nfun bar() {}\n"),
    truncated = false,
    symbolPath = "com.foo.Bar",
    vcsRevision = null,
    thread = emptyList(),
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
  )

  private fun call(tool: String, args: JsonObject): McpToolCallResult {
    return runBlocking {
      tools[tool]!!.call(args) as McpToolCallResult
    }
  }

  private fun text(result: McpToolCallResult): String =
    (result.content[0] as McpToolCallResultContent.Text).text

  private fun parse(result: McpToolCallResult): JsonObject = Json.parseToJsonElement(text(result)).jsonObject

  private fun ids(result: McpToolCallResult): Set<String> =
    parse(result).getValue("items").jsonArray
      .map { it.jsonObject.getValue("id").jsonPrimitive.content }
      .toSet()

  private fun intField(result: McpToolCallResult, name: String): Int =
    parse(result).getValue(name).jsonPrimitive.content.toInt()

  /** Status as the agent actually sees it on the wire, keyed by item id. */
  private fun statuses(result: McpToolCallResult): Map<String, String> =
    parse(result).getValue("items").jsonArray.associate {
      it.jsonObject.getValue("id").jsonPrimitive.content to
        it.jsonObject.getValue("status").jsonPrimitive.content
    }

  /**
   * The connection chip has no other source of truth: the MCP server belongs to the IDE, so a tool
   * call landing here is the only evidence that an agent reached the plugin. If a tool ever stops
   * recording, the chip goes quietly wrong instead of failing loudly.
   */
  fun testEveryToolRecordsThatTheAgentReachedUs() {
    val store = FeedbackStore.getInstance(project)
    val activity = McpActivity.getInstance(project)
    store.deleteAll()

    val invocations = listOf(
      "feedback_list" to buildJsonObject { },
      "feedback_acknowledge" to idsArray("nothing"),
      "feedback_resolve" to buildJsonObject { putString("id", "r1"); putString("summary", "done") },
      "feedback_dismiss" to buildJsonObject { putString("id", "d1"); putString("reason", "no") },
      "feedback_reply" to buildJsonObject { putString("id", "p1"); putString("message", "why?") },
      "feedback_clear_resolved" to buildJsonObject { },
      "feedback_watch" to buildJsonObject { putInt("timeoutSeconds", 1); putInt("batchWindowSeconds", 1) },
    )

    for ((tool, args) in invocations) {
      // resolve/dismiss/reply need a live item, and each one consumes it.
      store.add(item("r1"))
      store.add(item("d1"))
      store.add(item("p1"))

      call(tool, args)

      assertEquals("$tool did not record activity", tool, activity.lastCall?.name)
    }

    store.deleteAll()
  }

  fun testAllSevenToolsRegistered() {
    val expected = setOf(
      "feedback_list",
      "feedback_watch",
      "feedback_acknowledge",
      "feedback_resolve",
      "feedback_dismiss",
      "feedback_reply",
      "feedback_clear_resolved",
    )
    assertTrue("all 7 feedback tools registered", tools.keys.containsAll(expected))
    assertTrue("no built-in tool shadows a feedback_ prefix", tools.keys.none { it.startsWith("feedback_") && it !in expected })
  }

  fun testListDefaultsToPendingAndAcknowledged() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("p", Status.PENDING))
    store.add(item("a", Status.ACKNOWLEDGED))
    store.add(item("r", Status.RESOLVED))
    store.add(item("d", Status.DISMISSED))

    val result = call("feedback_list", buildJsonObject {})
    assertEquals("PENDING + ACKNOWLEDGED only", setOf("p", "a"), ids(result))
    assertEquals(1, intField(result, "totalPending"))
    assertEquals(mapOf("p" to "PENDING", "a" to "ACKNOWLEDGED"), statuses(result))
    store.deleteAll()
  }

  fun testAcknowledgeBatch() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1", Status.PENDING))
    store.add(item("2", Status.PENDING))
    store.add(item("3", Status.PENDING))

    val result = call("feedback_acknowledge", idsArray("1", "2", "3"))
    assertEquals(3, intField(result, "affected"))
    assertEquals(Status.ACKNOWLEDGED, store.byId("1")!!.status)
    store.deleteAll()
  }

  fun testResolveRequiresSummaryAndAppendsMessage() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1", Status.PENDING))

    val ok = call("feedback_resolve", buildJsonObject { putString("id", "1"); putString("summary", "done it") })
    assertFalse(ok.isError)
    assertEquals(Status.RESOLVED, store.byId("1")!!.status)
    assertEquals("done it", store.byId("1")!!.thread.single().body)
    assertEquals(Author.AGENT, store.byId("1")!!.thread.single().author)
    store.deleteAll()
  }

  fun testClearResolvedDoesNotTouchPendingOrAcknowledged() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("pending", Status.PENDING))
    store.add(item("acked", Status.ACKNOWLEDGED))
    store.add(item("resolved", Status.RESOLVED))
    store.add(item("dismissed", Status.DISMISSED))

    val result = call("feedback_clear_resolved", buildJsonObject {})
    assertEquals(2, intField(result, "deleted"))
    assertNotNull("PENDING survives", store.byId("pending"))
    assertNotNull("ACKNOWLEDGED survives", store.byId("acked"))
    assertNull("RESOLVED gone", store.byId("resolved"))
    assertNull("DISMISSED gone", store.byId("dismissed"))
    store.deleteAll()
  }

  fun testListAfterAckStillShowsItem() {
    // Agent ack then "restart": feedback_list must still show ACKNOWLEDGED items (no vanishing).
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1", Status.PENDING))
    call("feedback_acknowledge", idsArray("1"))

    val result = call("feedback_list", buildJsonObject {})
    assertTrue("acknowledged item still visible", "1" in ids(result))
    // Visible is not enough: a resumed agent must be able to tell it already saw this one.
    assertEquals("ACKNOWLEDGED", statuses(result).getValue("1"))
    store.deleteAll()
  }

  fun testReplyPreservesStatus() {
    val store = FeedbackStore.getInstance(project)
    store.deleteAll()
    store.add(item("1", Status.ACKNOWLEDGED))
    call("feedback_reply", buildJsonObject { putString("id", "1"); putString("message", "which line exactly?") })
    assertEquals(Status.ACKNOWLEDGED, store.byId("1")!!.status)
    assertEquals(1, store.byId("1")!!.thread.size)
    store.deleteAll()
  }
}