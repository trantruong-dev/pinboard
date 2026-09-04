package dev.pinboard.mcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpActivityTest : BasePlatformTestCase() {

  private val activity get() = McpActivity.getInstance(project)

  fun testRecordCapturesTheToolAndTheMoment() {
    val before = System.currentTimeMillis()
    activity.record("feedback_list")

    val call = activity.lastCall
    assertNotNull(call)
    assertEquals("feedback_list", call!!.name)
    assertTrue("timestamp must be the call's own moment", call.at >= before)
  }

  fun testLastCallReflectsTheMostRecentTool() {
    activity.record("feedback_list")
    activity.record("feedback_watch")

    assertEquals("feedback_watch", activity.lastCall?.name)
  }

  /**
   * Hammers the field from two threads and checks nothing partial or garbage is ever observed.
   *
   * To be clear about what this does not do: it cannot fail if `lastCall` is split back into two
   * volatiles, because a torn read would still yield a valid name and a valid timestamp - just from
   * different calls. That guarantee is structural, held by [ToolCall] being one immutable value, and
   * no test can substitute for it. This is a smoke test for the field under contention, no more.
   */
  fun testConcurrentRecordsAreNeverObservedPartially() {
    val seen = CopyOnWriteArrayList<ToolCall>()
    val stop = CountDownLatch(1)
    val reader = Thread {
      while (stop.count > 0) activity.lastCall?.let { seen.add(it) }
    }
    reader.start()

    val names = listOf("feedback_list", "feedback_watch", "feedback_resolve")
    repeat(300) { activity.record(names[it % names.size]) }
    stop.countDown()
    reader.join(5_000)

    assertFalse("reader observed nothing", seen.isEmpty())
    for (call in seen) {
      assertTrue("observed a tool name that was never recorded: ${call.name}", call.name in names)
      assertTrue("observed a call with no timestamp", call.at > 0)
    }
  }

  /**
   * The log runs for the whole IDE session and a watch loop can append every minute, so an unbounded
   * buffer would be a slow leak in a panel most users leave open all day.
   */
  fun testLogIsBounded() {
    repeat(250) { activity.append("line $it") }

    val snapshot = activity.snapshot()
    assertEquals(200, snapshot.size)
    assertTrue("oldest entries must be dropped, not newest", snapshot.last().endsWith("line 249"))
  }

  /**
   * Tool calls arrive on the MCP server's coroutine threads while the footer reads on the EDT. This
   * is the cross-thread visibility the volatile field exists for.
   */
  fun testWritesFromAnotherThreadAreVisible() {
    val done = CountDownLatch(1)
    Thread {
      activity.record("feedback_resolve")
      done.countDown()
    }.start()

    assertTrue("worker did not finish", done.await(5, TimeUnit.SECONDS))
    assertEquals("feedback_resolve", activity.lastCall?.name)
  }

  fun testSubscribeReplaysTheBacklogThenStreams() {
    activity.append("before subscribing")

    val seen = CopyOnWriteArrayList<String>()
    activity.subscribe(testRootDisposable) { seen.add(it) }
    activity.record("feedback_acknowledge")

    assertTrue("backlog was not replayed", seen.any { it.contains("before subscribing") })
    assertTrue("later lines did not stream", seen.any { it.contains("feedback_acknowledge") })
  }

  /**
   * [McpActivity.record] runs inside every tool before the tool does any work, so a listener that
   * throws would surface to the agent as the feedback tool itself failing. Telemetry must never be
   * able to break the operation it observes.
   */
  fun testAListenerThatThrowsCannotFailTheToolCall() {
    activity.subscribe(testRootDisposable) { error("this listener is broken") }

    val survivor = CopyOnWriteArrayList<String>()
    activity.subscribe(testRootDisposable) { survivor.add(it) }

    activity.record("feedback_list")

    assertEquals("feedback_list", activity.lastCall?.name)
    assertTrue("a healthy listener must still be notified", survivor.any { it.contains("feedback_list") })
  }
}
