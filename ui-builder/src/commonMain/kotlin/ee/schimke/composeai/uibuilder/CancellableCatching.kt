package ee.schimke.composeai.uibuilder

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending work: a cancellation is rethrown rather than reported as a failure,
 * so a cancelled load never surfaces as an error message or keeps running after its scope is gone.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Throwable) {
    Result.failure(failure)
  }
