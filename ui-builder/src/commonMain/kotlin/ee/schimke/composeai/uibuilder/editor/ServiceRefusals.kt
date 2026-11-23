package ee.schimke.composeai.uibuilder.editor

/**
 * Remembers that the host's service refused this page outright (`HTTP 401`, `HTTP 403`), so a call
 * that can only be refused the same way is not made again.
 *
 * Such a refusal is about who is asking, not what: a visitor who is not signed in may not export
 * any document, so every palette thumbnail the browser cannot compile itself would otherwise ask
 * and be refused, one request each. Signing in reloads the page, which starts a new latch.
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
