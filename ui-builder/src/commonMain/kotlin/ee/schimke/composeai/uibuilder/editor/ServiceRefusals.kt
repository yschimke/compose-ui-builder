package ee.schimke.composeai.uibuilder.editor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers that the host's service refused this page outright (`HTTP 401`, `HTTP 403`), so a call
 * that can only be refused the same way is not made again.
 *
 * Such a refusal is about who is asking, not what: a visitor who is not signed in may not export
 * any document, so every palette thumbnail the browser cannot compile itself would otherwise ask
 * and be refused, one request each. Signing in reloads the page, which starts a new latch.
 *
 * Until the service has answered once, calls go one at a time: the palette asks for several
 * thumbnails at once, and each of those sent before the first refusal came back was refused too.
 * After the first success they run side by side again.
 */
internal class ServiceRefusals {
  /** Why the service refused this page, once it has. */
  var refusal: String? = null
    private set

  /**
   * [block]'s answer, or a failure without calling it once the service has refused this page. A
   * failure whose message is an `HTTP 401` or `HTTP 403` (the browser host's fetch error, which a
   * JavaScript `Error` prefixes with `Error: `) is remembered.
   */
  suspend fun <T> call(block: suspend () -> T): T {
    refusal?.let { throw IllegalStateException(it) }
    if (!answered) {
      // A call that queued here behind the first one runs side by side once that one succeeded,
      // rather than holding the lock through its own request.
      val first = untilAnswered.withLock {
        refusal?.let { throw IllegalStateException(it) }
        if (answered) null else Answer(attempt(block)).also { answered = true }
      }
      if (first != null) return first.value
    }
    return attempt(block)
  }

  private class Answer<T>(val value: T)

  /** Whether the service has answered a call with success yet. */
  private var answered = false

  private val untilAnswered = Mutex()

  private suspend fun <T> attempt(block: suspend () -> T): T {
    try {
      return block()
    } catch (failure: IllegalStateException) {
      val message = failure.message.orEmpty()
      if (REFUSED.containsMatchIn(message)) refusal = message
      throw failure
    }
  }

  private companion object {
    val REFUSED = Regex("""^(Error: )?HTTP 40[13]\b""")
  }
}
