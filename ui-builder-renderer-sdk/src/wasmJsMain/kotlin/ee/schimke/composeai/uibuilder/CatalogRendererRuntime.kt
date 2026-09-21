@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2

private var latestSnapshot: UiBuilderInspectionSnapshot? = null
private var completedRenderRequestId: String? = null
private lateinit var endpoint: CatalogRuntimeProtocolEndpoint

private data class RenderRequest(
  val requestId: String,
  val documentId: String,
  val revision: Int,
  val surface: UiBuilderRendererSurfaceV2,
)

private data class PendingRender(val document: UiBuilderDocument, val request: RenderRequest)

private class RuntimeRenderState(initial: PendingRender) {
  var render by mutableStateOf(initial)
}

/**
 * Starts the opaque-origin renderer frame and delegates only the Compose drawing to its catalog.
 *
 * The SDK owns identity, origin locking, request correlation and measured-response timing. Catalog
 * code owns [content], including its real components, frame, themes and structural adapters.
 */
fun startCatalogRenderer(
  actionDispatcher: CatalogRuntimeActionDispatcher,
  content:
    @Composable
    (
      document: UiBuilderDocument,
      surface: UiBuilderRendererSurfaceV2,
      renderSessionId: String,
      onInspectionSnapshot: (UiBuilderInspectionSnapshot) -> Unit,
    ) -> Unit,
) {
  val runtimeId = runtimeIdFromPath()
  var renderState: RuntimeRenderState? = null
  endpoint = CatalogRuntimeProtocolEndpoint(runtimeId)
  installRuntimeReceiver { origin, encoded ->
    when (val command = endpoint.receive(origin, sourceIsParent = true, encoded)) {
      null -> Unit
      is CatalogRuntimeCommand.Reply -> postRuntimeMessage(endpoint.encode(command.message))
      is CatalogRuntimeCommand.Render -> {
        val surface = command.surface ?: return@installRuntimeReceiver
        val pending =
          PendingRender(
            document = command.document,
            request =
              RenderRequest(
                command.requestId,
                command.document.id,
                command.document.revision,
                surface,
              ),
          )
        completedRenderRequestId = null
        latestSnapshot = null
        val state = renderState
        if (state == null) {
          RuntimeRenderState(pending).also {
            renderState = it
            // ComposeViewport creates browser frame machinery and must not be entered re-entrantly
            // from the window message callback that delivered the document. Start it on the next
            // task with the already-populated state so its first scene contains catalog content.
            scheduleRuntimeViewport { startRuntimeViewport(it, content) }
          }
        } else {
          // DOM message callbacks sit outside Compose's mutable snapshot. Once a viewport exists,
          // explicitly apply later revisions so its active scene invalidates reliably.
          Snapshot.withMutableSnapshot { state.render = pending }
          Snapshot.sendApplyNotifications()
        }
      }
      is CatalogRuntimeCommand.DispatchAction -> {
        when (val result = actionDispatcher.dispatch(command.action, latestSnapshot)) {
          UiBuilderSemanticActionResult.Applied ->
            scheduleActionCompletion { completeAction(command.requestId, command.action) }
          is UiBuilderSemanticActionResult.Rejected ->
            postRuntimeMessage(
              endpoint.encode(
                endpoint.actionRejected(command.requestId, result.code, result.message)
              )
            )
        }
      }
    }
  }
}

private fun startRuntimeViewport(
  state: RuntimeRenderState,
  content:
    @Composable
    (
      document: UiBuilderDocument,
      surface: UiBuilderRendererSurfaceV2,
      renderSessionId: String,
      onInspectionSnapshot: (UiBuilderInspectionSnapshot) -> Unit,
    ) -> Unit,
) {
  ComposeViewport(viewportContainerId = "composeApp") {
    Box(Modifier.fillMaxSize()) {
      val pending = state.render
      val current = pending.document
      val request = pending.request
      content(current, request.surface, request.requestId) { snapshot ->
        if (
          snapshot.documentId != request.documentId || snapshot.documentRevision != request.revision
        )
          return@content
        latestSnapshot = snapshot
        if (completedRenderRequestId == request.requestId) return@content
        scheduleMeasuredResponse(
          if (snapshot.generation.measuredNodeIds.isEmpty()) UNMEASURED_SETTLE_MS
          else MEASURED_SETTLE_MS
        ) {
          if (completedRenderRequestId == request.requestId) return@scheduleMeasuredResponse
          val response = endpoint.rendered(request.requestId, snapshot)
          if (response.type == "rendered") completedRenderRequestId = request.requestId
          postRuntimeMessage(endpoint.encode(response))
        }
      }
      LaunchedEffect(current.id, current.revision) { markRendererReady() }
    }
  }
}

private fun completeAction(requestId: String, action: CatalogRuntimeAction) {
  val snapshot = latestSnapshot
  val response =
    if (
      snapshot == null ||
        snapshot.documentId != action.documentId ||
        snapshot.documentRevision != action.documentRevision
    ) {
      endpoint.actionRejected(
        requestId,
        "STALE_ACTION_COMPLETION",
        "renderer no longer has the action's exact document revision",
      )
    } else endpoint.actionDispatched(requestId, snapshot)
  postRuntimeMessage(endpoint.encode(response))
}

@JsFun(
  """() => {
    const parts = globalThis.location.pathname.split('/').filter(Boolean);
    const marker = parts.lastIndexOf('runtime');
    const runtimeId = marker >= 0 ? parts[marker + 1] : '';
    if (!runtimeId || !/^[A-Za-z0-9._-]+$/.test(runtimeId) || runtimeId === 'latest' || runtimeId === 'current') {
      throw new Error('renderer must be loaded from an exact /ui-builder/runtime/<runtimeId>/ path');
    }
    return runtimeId;
  }"""
)
private external fun runtimeIdFromPath(): String

private fun installRuntimeReceiver(handler: (String, String) -> Unit): Unit =
  js(
    """(function () {
      globalThis.addEventListener('message', function (event) {
        if (event.source !== globalThis.parent || typeof event.data !== 'string') return;
        if (globalThis.__uiBuilderParentOrigin && event.origin !== globalThis.__uiBuilderParentOrigin) return;
        if (!globalThis.__uiBuilderParentOrigin) globalThis.__uiBuilderParentOrigin = event.origin;
        handler(event.origin, event.data);
      });
    })()"""
  )

private fun postRuntimeMessage(encoded: String): Unit =
  js("globalThis.parent.postMessage(encoded, globalThis.__uiBuilderParentOrigin)")

private const val MEASURED_SETTLE_MS = 32
private const val UNMEASURED_SETTLE_MS = 250

private fun scheduleMeasuredResponse(delayMs: Int, callback: () -> Unit): Unit =
  js(
    """(function () {
      const token = (globalThis.__uiBuilderMeasureToken || 0) + 1;
      globalThis.__uiBuilderMeasureToken = token;
      // Opaque sandbox frames can have requestAnimationFrame suspended while offscreen or behind
      // another editor surface. A short cancellable settle window still coalesces layout snapshots
      // without tying protocol completion to browser visibility.
      setTimeout(function () {
        if (globalThis.__uiBuilderMeasureToken !== token) return;
        callback();
      }, delayMs);
    })()"""
  )

private fun scheduleRuntimeViewport(callback: () -> Unit): Unit = js("setTimeout(callback, 0)")

private fun scheduleActionCompletion(callback: () -> Unit): Unit =
  js(
    """requestAnimationFrame(function () {
      requestAnimationFrame(function () { requestAnimationFrame(callback); });
    })"""
  )

@JsFun("() => document.documentElement.dataset.uiBuilderRendererReady = 'true'")
private external fun markRendererReady()
