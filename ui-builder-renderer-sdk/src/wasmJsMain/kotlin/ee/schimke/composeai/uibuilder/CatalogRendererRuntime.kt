@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceV2

private var document by mutableStateOf<UiBuilderDocument?>(null)
private var renderRequest by mutableStateOf<RenderRequest?>(null)
private var latestSnapshot: UiBuilderInspectionSnapshot? = null
private var completedRenderRequestId: String? = null
private lateinit var endpoint: CatalogRuntimeProtocolEndpoint

private data class RenderRequest(
  val requestId: String,
  val documentId: String,
  val revision: Int,
  val surface: UiBuilderRendererSurfaceV2,
)

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
  endpoint = CatalogRuntimeProtocolEndpoint(runtimeId)
  installRuntimeReceiver { origin, encoded ->
    when (val command = endpoint.receive(origin, sourceIsParent = true, encoded)) {
      null -> Unit
      is CatalogRuntimeCommand.Reply -> postRuntimeMessage(endpoint.encode(command.message))
      is CatalogRuntimeCommand.Render -> {
        val surface = command.surface ?: return@installRuntimeReceiver
        renderRequest =
          RenderRequest(
            command.requestId,
            command.document.id,
            command.document.revision,
            surface,
          )
        completedRenderRequestId = null
        latestSnapshot = null
        document = command.document
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
  ComposeViewport(viewportContainerId = "composeApp") {
    document?.let { current ->
      val request = renderRequest ?: return@let
      content(current, request.surface, request.requestId) { snapshot ->
        if (
          snapshot.documentId != request.documentId || snapshot.documentRevision != request.revision
        )
          return@content
        latestSnapshot = snapshot
        if (completedRenderRequestId == request.requestId) return@content
        scheduleMeasuredResponse {
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

private fun scheduleMeasuredResponse(callback: () -> Unit): Unit =
  js(
    """(function () {
      const token = (globalThis.__uiBuilderMeasureToken || 0) + 1;
      globalThis.__uiBuilderMeasureToken = token;
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          if (globalThis.__uiBuilderMeasureToken !== token) return;
          callback();
        });
      });
    })()"""
  )

private fun scheduleActionCompletion(callback: () -> Unit): Unit =
  js(
    """requestAnimationFrame(function () {
      requestAnimationFrame(function () { requestAnimationFrame(callback); });
    })"""
  )

@JsFun("() => document.documentElement.dataset.uiBuilderRendererReady = 'true'")
private external fun markRendererReady()
