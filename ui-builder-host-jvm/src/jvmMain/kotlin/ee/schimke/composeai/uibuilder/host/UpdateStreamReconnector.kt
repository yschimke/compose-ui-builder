package ee.schimke.composeai.uibuilder.host

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Brings a design's pushed-update stream back after its socket drops.
 *
 * [onDisconnected] may be called from any thread, any number of times: signals conflate, so a burst
 * of close and error callbacks costs one recovery. Recovery retries [reopen] with [reconnectDelay]
 * backoff until it succeeds, reporting each wait through [onStatus] and clearing it with `null`
 * once the stream is back. A disconnect reported while a recovery is running runs one more after
 * it, so a socket that dies straight after reopening is not left dead.
 *
 * Cancelling [scope] — the session closing — stops the retries; nothing outlives it.
 */
internal class UpdateStreamReconnector(
  scope: CoroutineScope,
  private val reopen: suspend () -> Unit,
  private val onStatus: (String?) -> Unit,
  private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
  private val disconnects = Channel<Unit>(Channel.CONFLATED)

  init {
    scope.launch { for (signal in disconnects) recover() }
  }

  fun onDisconnected() {
    disconnects.trySend(Unit)
  }

  private suspend fun recover() {
    var attempt = 0
    while (true) {
      val wait = reconnectDelay(attempt)
      onStatus(
        "Disconnected from the server · reconnecting in ${wait.inWholeSeconds.coerceAtLeast(1)}s"
      )
      sleep(wait)
      try {
        reopen()
        onStatus(null)
        return
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        attempt++
      }
    }
  }
}

/** The wait before reconnect [attempt] (0-based): doubling from half a second, capped at 30s. */
internal fun reconnectDelay(attempt: Int): Duration =
  (RECONNECT_INITIAL_MILLIS shl attempt.coerceIn(0, 6))
    .coerceAtMost(RECONNECT_MAX_MILLIS)
    .milliseconds

private const val RECONNECT_INITIAL_MILLIS = 500L
private const val RECONNECT_MAX_MILLIS = 30_000L
