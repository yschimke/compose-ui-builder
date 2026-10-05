package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CancellableCatchingTimeoutTest {
  @Test
  fun `an inner timeout is captured as a failure`() = runBlocking {
    val result = runCatchingCancellable { withTimeout(10) { awaitCancellation() } }
    assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
  }
}
