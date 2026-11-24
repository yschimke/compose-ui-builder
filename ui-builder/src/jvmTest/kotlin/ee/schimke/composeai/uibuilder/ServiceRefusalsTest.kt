package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.ServiceRefusals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ServiceRefusalsTest {
  @Test
  fun `a refusal stops later calls, any other failure does not`() = runBlocking {
    val refusals = ServiceRefusals()
    var calls = 0

    assertFailsWith<IllegalStateException> {
      refusals.call {
        calls++
        throw IllegalStateException("Error: HTTP 500: boom")
      }
    }
    assertNull(refusals.refusal)
    assertEquals(
      "ok",
      refusals.call {
        calls++
        "ok"
      },
    )

    assertFailsWith<IllegalStateException> {
      refusals.call<String> {
        calls++
        throw IllegalStateException("Error: HTTP 401: authentication is required")
      }
    }
    assertEquals(3, calls)
    // Refused for every document now: nothing more is sent.
    assertFailsWith<IllegalStateException> {
      refusals.call {
        calls++
        "never"
      }
    }
    assertEquals(3, calls)
  }

  @Test
  fun `a forbidden answer without the JavaScript prefix is a refusal too`() = runBlocking {
    val refusals = ServiceRefusals()
    assertFailsWith<IllegalStateException> {
      refusals.call<Unit> {
        throw IllegalStateException("HTTP 403: UI-builder export access required")
      }
    }
    assertEquals("HTTP 403: UI-builder export access required", refusals.refusal)
  }

  @Test
  fun `calls sent together before the first answer cost one refused request`() = runBlocking {
    val refusals = ServiceRefusals()
    var calls = 0
    val answers =
      (1..6)
        .map {
          async {
            runCatching {
              refusals.call<Unit> {
                calls++
                delay(10)
                throw IllegalStateException("Error: HTTP 401: authentication is required")
              }
            }
          }
        }
        .awaitAll()
    assertEquals(1, calls)
    assertEquals(6, answers.count { it.isFailure })
  }

  @Test
  fun `after the first success calls run side by side`() = runBlocking {
    val refusals = ServiceRefusals()
    refusals.call { "first" }
    var inFlight = 0
    var most = 0
    (1..4)
      .map {
        async {
          refusals.call {
            inFlight++
            most = maxOf(most, inFlight)
            delay(10)
            inFlight--
          }
        }
      }
      .awaitAll()
    assertEquals(4, most)
  }
}
