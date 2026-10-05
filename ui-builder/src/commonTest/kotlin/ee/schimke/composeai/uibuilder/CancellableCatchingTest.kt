package ee.schimke.composeai.uibuilder

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CancellableCatchingTest {
  @Test
  fun `a value and an ordinary failure are captured`() {
    assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    assertTrue(runCatchingCancellable { error("boom") }.isFailure)
  }

  @Test
  fun `a cancellation is rethrown rather than reported as a failure`() {
    assertFailsWith<CancellationException> {
      runCatchingCancellable { throw CancellationException("left the screen") }
    }
  }
}
