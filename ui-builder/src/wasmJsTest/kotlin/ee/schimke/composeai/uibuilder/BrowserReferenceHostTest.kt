@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, DelicateCoroutinesApi::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.reference.ReferenceImportOutcome
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.yield

/**
 * The paste wait, cancelled. `LiveSessionApp` loops `while (true)` around
 * [BrowserReferenceHost.awaitPaste], so cancellation must propagate rather than return `Refused`,
 * or the loop spins without yielding and freezes the tab.
 */
class BrowserReferenceHostTest {

  @Test
  fun `a cancelled paste wait is cancelled rather than refused`(): Promise<JsAny?> =
    GlobalScope.promise {
      val host =
        BrowserReferenceHost(
          designId = "paste-test",
          http =
            UiBuilderProtocolHttpClient(
              actorId = "test",
              endpoint = "/api/ui-builder/v1/protocol",
              transport = BrowserUiBuilderHttpTransport(),
              requestIds = MonotonicUiBuilderRequestIds("test"),
            ),
        )
      var outcome: ReferenceImportOutcome? = null
      val waiting = launch { outcome = host.awaitPaste() }
      // Into the wait: nothing is pasted in a test, so it stays there until cancelled.
      yield()
      waiting.cancelAndJoin()

      assertTrue(waiting.isCancelled)
      assertNull(outcome, "a cancelled wait must not return an outcome the loop would go round on")
      null
    }
}
