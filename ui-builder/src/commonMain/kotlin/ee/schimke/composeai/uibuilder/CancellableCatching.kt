package ee.schimke.composeai.uibuilder

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * [runCatching] for suspending work: a cancellation is rethrown rather than reported as a failure,
 * so a cancelled load never surfaces as an error message or keeps running after its scope is gone.
 * A [TimeoutCancellationException] from an inner `withTimeout` is the operation failing, not the
 * caller being cancelled, so it is captured like any other failure.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (timeout: TimeoutCancellationException) {
    Result.failure(timeout)
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Throwable) {
    Result.failure(failure)
  }
