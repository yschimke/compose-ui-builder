@file:OptIn(
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder.renderer

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.uibuilder.CatalogRuntimeAction
import ee.schimke.composeai.uibuilder.CatalogRuntimeCommand
import ee.schimke.composeai.uibuilder.CatalogRuntimeProtocolEndpoint
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.UiBuilderSemanticActionController
import ee.schimke.composeai.uibuilder.UiBuilderSemanticActionResult
import ee.schimke.composeai.uibuilder.UiBuilderSurface

private var document by mutableStateOf<UiBuilderDocument?>(null)
private var renderRequest by mutableStateOf<RenderRequest?>(null)
private var latestSnapshot: UiBuilderInspectionSnapshot? = null
private var completedRenderRequestId: String? = null
private lateinit var endpoint: CatalogRuntimeProtocolEndpoint
private val actionController = UiBuilderSemanticActionController()

private data class RenderRequest(val requestId: String, val documentId: String, val revision: Int)

fun main() {
  val runtimeId = runtimeIdFromPath()
  endpoint = CatalogRuntimeProtocolEndpoint(runtimeId)
  installRuntimeReceiver { origin, encoded ->
    when (val command = endpoint.receive(origin, sourceIsParent = true, encoded)) {
      null -> Unit
      is CatalogRuntimeCommand.Reply -> postRuntimeMessage(endpoint.encode(command.message))
      is CatalogRuntimeCommand.Render -> {
        renderRequest =
          RenderRequest(command.requestId, command.document.id, command.document.revision)
        completedRenderRequestId = null
        latestSnapshot = null
        document = command.document
      }
      is CatalogRuntimeCommand.DispatchAction -> {
        when (val result = actionController.dispatch(command.action, latestSnapshot)) {
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
      UiBuilderSurface(
        document = current,
        editorOverlay = false,
        runtimeActionController = actionController,
        renderSessionId = request.requestId,
        onInspectionSnapshot = { snapshot ->
          if (
            snapshot.documentId != request.documentId ||
              snapshot.documentRevision != request.revision
          )
            return@UiBuilderSurface
          latestSnapshot = snapshot
          if (completedRenderRequestId == request.requestId) return@UiBuilderSurface
          scheduleMeasuredResponse {
            if (completedRenderRequestId == request.requestId) return@scheduleMeasuredResponse
            val response = endpoint.rendered(request.requestId, snapshot)
            if (response.type == "rendered") completedRenderRequestId = request.requestId
            postRuntimeMessage(endpoint.encode(response))
          }
        },
      )
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
