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
import ee.schimke.composeai.uibuilder.CatalogRuntimeCommand
import ee.schimke.composeai.uibuilder.CatalogRuntimeProtocolEndpoint
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.UiBuilderSurface
import ee.schimke.composeai.uibuilder.UiBuilderSurfaceInputController

private var document by mutableStateOf<UiBuilderDocument?>(null)
private var renderRequestId by mutableStateOf<String?>(null)
private var latestSnapshot: UiBuilderInspectionSnapshot? = null
private lateinit var endpoint: CatalogRuntimeProtocolEndpoint
private val inputController = UiBuilderSurfaceInputController()

fun main() {
  val runtimeId = runtimeIdFromPath()
  endpoint = CatalogRuntimeProtocolEndpoint(runtimeId)
  installRuntimeReceiver { origin, encoded ->
    when (val command = endpoint.receive(origin, sourceIsParent = true, encoded)) {
      null -> Unit
      is CatalogRuntimeCommand.Reply -> postRuntimeMessage(endpoint.encode(command.message))
      is CatalogRuntimeCommand.Render -> {
        renderRequestId = command.requestId
        latestSnapshot = null
        document = command.document
      }
      is CatalogRuntimeCommand.DispatchInput -> {
        if (!runtimeInputInsideViewport(command.input.x, command.input.y)) {
          postRuntimeMessage(
            endpoint.encode(
              endpoint.inputRejected(
                command.requestId,
                "INPUT_OUT_OF_BOUNDS",
                "input coordinates must fall inside the renderer viewport",
              )
            )
          )
        } else if (
          !inputController.dispatch(command.input) {
            scheduleInputCompletion { completeInput(command.requestId) }
          }
        ) {
          postRuntimeMessage(
            endpoint.encode(
              endpoint.inputRejected(
                command.requestId,
                "RENDERER_NOT_READY",
                "renderer input surface has not been installed",
              )
            )
          )
        }
      }
    }
  }
  ComposeViewport(viewportContainerId = "composeApp") {
    document?.let { current ->
      UiBuilderSurface(
        document = current,
        editorOverlay = false,
        runtimeInputController = inputController,
        onInspectionSnapshot = { snapshot ->
          latestSnapshot = snapshot
          val requestId = renderRequestId ?: return@UiBuilderSurface
          scheduleMeasuredResponse(endpoint.encode(endpoint.rendered(requestId, snapshot)))
        },
      )
      LaunchedEffect(current.id, current.revision) { markRendererReady() }
    }
  }
}

private fun completeInput(requestId: String) {
  val snapshot = latestSnapshot
  val response =
    if (snapshot == null) {
      endpoint.inputRejected(
        requestId,
        "NO_INSPECTION",
        "renderer has no stable inspection for the active document",
      )
    } else endpoint.inputDispatched(requestId, snapshot)
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

private fun scheduleMeasuredResponse(encoded: String): Unit =
  js(
    """(function () {
      const token = (globalThis.__uiBuilderMeasureToken || 0) + 1;
      globalThis.__uiBuilderMeasureToken = token;
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          if (globalThis.__uiBuilderMeasureToken !== token) return;
          globalThis.parent.postMessage(encoded, globalThis.__uiBuilderParentOrigin);
        });
      });
    })()"""
  )

private fun scheduleInputCompletion(callback: () -> Unit): Unit =
  js(
    """requestAnimationFrame(function () {
      requestAnimationFrame(function () { requestAnimationFrame(callback); });
    })"""
  )

@JsFun("(x, y) => x >= 0 && y >= 0 && x < globalThis.innerWidth && y < globalThis.innerHeight")
private external fun runtimeInputInsideViewport(x: Double, y: Double): Boolean

@JsFun("() => document.documentElement.dataset.uiBuilderRendererReady = 'true'")
private external fun markRendererReady()
