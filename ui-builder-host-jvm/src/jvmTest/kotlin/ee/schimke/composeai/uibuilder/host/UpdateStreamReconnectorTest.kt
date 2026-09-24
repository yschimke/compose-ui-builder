package ee.schimke.composeai.uibuilder.host

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class UpdateStreamReconnectorTest {
  @Test
  fun `backoff doubles from half a second and caps at thirty seconds`() {
    assertEquals(
      listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds),
      (0..6).map(::reconnectDelay),
    )
    assertEquals(30.seconds, reconnectDelay(40))
  }

  @Test
  fun `a dropped stream reopens after the first backoff and clears its status`() = runBlocking {
    val harness = Harness(this, failures = 0)
    harness.reconnector.onDisconnected()
    harness.settle()

    assertEquals(1, harness.reopens)
    assertEquals(listOf(500.milliseconds), harness.waits)
    assertNull(harness.statuses.last())
    assertEquals(
      "Disconnected from the server · reconnecting in 1s",
      harness.statuses.first(),
    )
    harness.scope.cancel()
  }

  @Test
  fun `a reopen that keeps failing backs off until it succeeds`() = runBlocking {
    val harness = Harness(this, failures = 3)
    harness.reconnector.onDisconnected()
    harness.settle()

    assertEquals(4, harness.reopens)
    assertEquals(listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds), harness.waits)
    assertNull(harness.statuses.last())
    harness.scope.cancel()
  }

  @Test
  fun `a burst of close and error callbacks costs one recovery`() = runBlocking {
    val harness = Harness(this, failures = 0)
    repeat(3) { harness.reconnector.onDisconnected() }
    harness.settle()

    assertEquals(1, harness.reopens)
    harness.scope.cancel()
  }

  @Test
  fun `a stream that drops again after reopening is reopened again`() = runBlocking {
    lateinit var harness: Harness
    harness =
      Harness(this, failures = 0) { if (harness.reopens == 1) harness.reconnector.onDisconnected() }
    harness.reconnector.onDisconnected()
    harness.settle()

    assertEquals(2, harness.reopens)
    harness.scope.cancel()
  }

  @Test
  fun `closing the session stops the retries`() = runBlocking {
    val harness = Harness(this, failures = Int.MAX_VALUE)
    harness.reconnector.onDisconnected()
    repeat(5) { yield() }
    harness.scope.cancel()
    val attempts = harness.reopens
    harness.settle()

    assertEquals(attempts, harness.reopens)
  }

  private class Harness(
    parent: CoroutineScope,
    private var failures: Int,
    private val afterReopen: () -> Unit = {},
  ) {
    val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    val waits = mutableListOf<Duration>()
    val statuses = mutableListOf<String?>()
    var reopens = 0

    val reconnector =
      UpdateStreamReconnector(
        scope,
        reopen = {
          reopens++
          if (failures-- > 0) throw IOException("connection refused")
          afterReopen()
        },
        onStatus = statuses::add,
        sleep = {
          waits += it
          yield()
        },
      )

    suspend fun settle() = repeat(50) { yield() }
  }
}
