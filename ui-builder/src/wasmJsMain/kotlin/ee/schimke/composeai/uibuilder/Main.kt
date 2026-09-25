@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.ComposeViewport
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.client.BrowserUiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.MonotonicUiBuilderRequestIds
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.editor.EditorOverlays
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasInspection
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasSurface
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeLive
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeNodeBounds
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.supportingText
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.NEW_DESIGN_ID
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetSample
import ee.schimke.composeai.uibuilder.local.CachedLocalText
import ee.schimke.composeai.uibuilder.local.CachingLocalCatalogSource
import ee.schimke.composeai.uibuilder.local.LocalCatalogSource
import ee.schimke.composeai.uibuilder.local.LocalDesignStorageException
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalSyncResult
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderService
import ee.schimke.composeai.uibuilder.local.localCheckoutRecord
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionCollector
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Image

/**
 * Let the editor own the right button.
 *
 * The editor answers a right-click on a layer with its own menu, and the browser answers the same
 * press with the page menu drawn on top of it. Only one of them can be the one that opens.
 */
@JsFun(
  """() => {
  const host = document.getElementById('composeApp');
  if (host) host.addEventListener('contextmenu', (event) => event.preventDefault());
}"""
)
private external fun suppressBrowserContextMenu()

fun main() {
  val rendererRuntimeId = sandboxRendererRuntimeId()
  if (rendererRuntimeId.isNotEmpty()) {
    // The sandboxed renderer draws into the page through its own runtime, not Compose, and says
    // nothing when it is done; the boot screen would sit on top of it.
    dismissBootScreen()
    MainScope().launch {
      val fixture =
        Json.parseToJsonElement(fetchText("jetcaster-discover-operations-v1.json")).jsonObject
      val fixtureDocument = UiBuilderReducer.replay(fixture).document
      // The checked-in benchmark predates retained runtimes and carries the placeholder
      // `candidate` pin. This isolated transport fixture gives that document the exact runtime
      // selected by the test shell; production documents arrive with this pin already persisted.
      val document =
        fixtureDocument.copy(
          catalogPin =
            JsonObject(
              fixtureDocument.catalogPin +
                ("nativeRuntimeId" to kotlinx.serialization.json.JsonPrimitive(rendererRuntimeId))
            )
        )
      mountSandboxRenderer(
        rendererRuntimeId,
        inspectionJson.encodeToString(UiBuilderDocument.serializer(), document),
      )
    }
    return
  }
  suppressBrowserContextMenu()
  if (!webGlAvailable()) {
    // Skiko draws through WebGL, and where a browser refuses a context `ComposeViewport` fails
    // inside a coroutine: the page is blank, and the reason reaches only the console. Say it where
    // a person is looking instead — a blank editor with no explanation is what sent somebody
    // hunting through `chrome://gpu` to find out why their design had "disappeared".
    dismissBootScreen()
    showWebGlRequiredMessage()
    return
  }
  // One registry for the page: the canvas asks it for the family a design names, the typeface
  // picker for the ones it lists, and a family either loads is then there for both.
  val fonts = browserFontRegistry()
  ComposeViewport(viewportContainerId = "composeApp") {
    ProvideUiBuilderFonts(fonts) {
      when {
        hostBridgeEnabled() -> HostBridgeApp()
        liveSessionEnabled() -> LiveSessionApp()
        else -> VisualFixtureApp(captureMode())
      }
    }
  }
}

/**
 * Whether this browser will give Skiko a WebGL context, asked before Compose starts.
 *
 * `webgl2` first, then `webgl`: Skiko prefers WebGL2 and falls back to WebGL1 for the 2D canvas
 * path, so either is enough for the editor to draw. The probe canvas is discarded — the context it
 * creates is one a real page would have made anyway.
 */
@JsFun(
  """() => {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch (e) {
    return false;
  }
}"""
)
private external fun webGlAvailable(): Boolean

/**
 * The message a browser without WebGL gets, in the container Compose would have drawn into.
 *
 * Plain DOM rather than a Compose fallback, because Compose is the thing that cannot start. The
 * design is not the problem and the text says so: the same URL works in a browser with WebGL, and
 * `design render` draws the design without a browser at all.
 */
@JsFun(
  """() => {
  const host = document.getElementById('composeApp');
  console.error('compose-ui-builder: no WebGL context; the editor cannot start.');
  if (!host) return;
  host.innerHTML =
    '<div style="font: 14px system-ui, -apple-system, sans-serif; color: #E6E1E5; background: #1C1B1F; padding: 24px; min-height: 100%; box-sizing: border-box;">' +
    '<h1 style="font-size: 18px; margin: 0 0 10px;">This browser cannot draw the editor</h1>' +
    '<p style="margin: 0 0 12px; max-width: 44em;">The Compose UI builder renders through WebGL, and this browser did not provide a WebGL context — so the editor would be a blank page.</p>' +
    '<p style="margin: 0 0 4px; max-width: 44em;">Usually one of these:</p>' +
    '<ul style="margin: 0 0 12px; padding-left: 1.2em; max-width: 44em;">' +
    '<li>hardware acceleration is off — check the browser&rsquo;s system settings, then restart it</li>' +
    '<li>the GPU is blocklisted — the browser&rsquo;s GPU page says why</li>' +
    '<li>the browser is in software rendering, as a remote or headless session often is</li>' +
    '</ul>' +
    '<p style="margin: 0; max-width: 44em;">The design is fine: this URL opens in a browser with WebGL, and <code>compose-preview-server design render</code> draws the same design without a browser.</p>' +
    '</div>';
}"""
)
private external fun showWebGlRequiredMessage()

@JsFun(
  """() => {
    const value = new URLSearchParams(globalThis.location.search).get('rendererRuntimeId') || '';
    if (value && (!/^[A-Za-z0-9._-]+$/.test(value) || value === 'latest' || value === 'current')) {
      throw new Error('rendererRuntimeId must be an exact safe runtime id');
    }
    return value;
  }"""
)
private external fun sandboxRendererRuntimeId(): String

@Composable
internal fun CatalogRuntimeCanvas(
  document: UiBuilderDocument,
  surface: UiBuilderCanvasSurface,
  selectedNodeId: String?,
  selectionEnabled: Boolean,
  onNodeSelected: (String) -> Unit,
  onInspectionSnapshot: (UiBuilderCanvasInspection) -> Unit,
) {
  val runtimeId = document.catalogPin["nativeRuntimeId"]?.jsonPrimitive?.contentOrNull.orEmpty()
  val surfaceId = remember { nextCatalogRuntimeSurfaceId() }
  // The runtime is sent the document and nothing else, and cannot fetch the design's uploaded
  // pictures from its sandbox, so the ones the editor has fetched travel inside it.
  val runtimeDocument = document.withInlinedUploadedAssets(LocalUiBuilderAssetBytes.current)
  val documentJson =
    remember(runtimeDocument) {
      inspectionJson.encodeToString(UiBuilderDocument.serializer(), runtimeDocument)
    }
  var lastInspection by remember(surfaceId) { mutableStateOf("") }
  var runtimeSnapshot by remember(surfaceId) { mutableStateOf<UiBuilderInspectionSnapshot?>(null) }
  var coordinates by remember(surfaceId) { mutableStateOf<LayoutCoordinates?>(null) }
  // Where the surface sits and how large it is drawn, in window pixels. A canvas zoom scales the
  // runtime's frame without re-rendering it, so its snapshot stays valid but has to be mapped
  // again.
  var placement by remember(surfaceId) { mutableStateOf<Rect?>(null) }
  // Compose's window coordinates are canvas pixels and the host `div` is placed in CSS pixels. On
  // a display whose `devicePixelRatio` is not 1 the two differ by exactly this density.
  val pixelsPerCssPixel = LocalDensity.current.density
  // Read in composition so opening or closing an editor menu recomposes this and re-stacks the
  // surface: a device frame sits above the canvas to take the pointer, which would bury the menu.
  val editorOverlayOpen = EditorOverlays.anyOpen
  LaunchedEffect(surfaceId, runtimeId, document.revision) {
    lastInspection = ""
    while (lastInspection.isEmpty()) {
      val encoded = readCatalogRuntimeInspection(surfaceId)
      if (encoded.isNotEmpty() && encoded != lastInspection) {
        lastInspection = encoded
        runCatching {
          inspectionJson.decodeFromString(UiBuilderInspectionSnapshot.serializer(), encoded)
        }
          .getOrNull()
          ?.takeIf { it.documentId == document.id && it.documentRevision == document.revision }
          ?.let { runtimeSnapshot = it }
      }
      delay(100)
    }
  }
  // Mapped into the editor whenever either side moves: a new snapshot, or the same snapshot under a
  // zoom or scroll. Mapped only once, the selection and drop overlays stayed where the canvas had
  // been when the runtime first answered, and drifted off the design as soon as it was zoomed.
  LaunchedEffect(runtimeSnapshot, placement) {
    val snapshot = runtimeSnapshot ?: return@LaunchedEffect
    val current = coordinates?.takeIf { it.isAttached } ?: return@LaunchedEffect
    onInspectionSnapshot(UiBuilderCanvasInspection(snapshot, snapshot.inEditorCoordinates(current)))
  }
  DisposableEffect(surfaceId) {
    mountCatalogRuntimeSurface(surfaceId)
    onDispose { disposeCatalogRuntimeSurface(surfaceId) }
  }
  SideEffect {
    mountCatalogRuntimeSurface(surfaceId)
    coordinates?.let { nextCoordinates ->
      positionCatalogRuntimeSurface(
        surfaceId,
        nextCoordinates,
        pixelsPerCssPixel,
        surface.positionVersion,
      )
    }
    updateCatalogRuntimeSurface(
      surfaceId = surfaceId,
      runtimeId = runtimeId,
      documentJson = documentJson,
      widthDp = surface.widthDp,
      heightDp = surface.heightDp,
      density = surface.density,
      mode = surface.mode.name.lowercase().replace('_', '-'),
      selectedNodeId = selectedNodeId.orEmpty(),
      selectionEnabled = selectionEnabled,
      editorOverlayOpen = editorOverlayOpen,
    )
  }
  Box(
    Modifier.fillMaxSize()
      // Catalog pixels live in the DOM layer immediately below Compose. Punch out only their exact
      // rectangle; editor-owned Compose overlays are later siblings and remain above the runtime.
      .drawWithContent {
        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
        drawContent()
      }
      .onGloballyPositioned { nextCoordinates ->
        coordinates = nextCoordinates
        placement =
          Rect(
            nextCoordinates.localToWindow(Offset.Zero),
            nextCoordinates.localToWindow(
              Offset(nextCoordinates.size.width.toFloat(), nextCoordinates.size.height.toFloat())
            ),
          )
        positionCatalogRuntimeSurface(
          surfaceId,
          nextCoordinates,
          pixelsPerCssPixel,
          surface.positionVersion,
        )
      }
  )
}

/**
 * Places the runtime's host `div` under the hole [CatalogRuntimeCanvas] punches in the canvas.
 *
 * The bounds Compose reports are canvas pixels, and the `div` is positioned in CSS pixels, so every
 * edge is divided by [pixelsPerCssPixel] on the way out. Written through unconverted, the host
 * landed at twice its offset and twice its size on a `devicePixelRatio` 2 display: the hole showed
 * the page's white body and the design appeared shifted down and right of it, further the more the
 * canvas was zoomed.
 */
private fun positionCatalogRuntimeSurface(
  surfaceId: String,
  coordinates: LayoutCoordinates,
  pixelsPerCssPixel: Float,
  positionVersion: Int,
) {
  val scale = pixelsPerCssPixel.takeIf { it > 0f } ?: 1f
  val visible = coordinates.boundsInWindow()
  val corners =
    listOf(
      coordinates.localToWindow(Offset.Zero),
      coordinates.localToWindow(Offset(coordinates.size.width.toFloat(), 0f)),
      coordinates.localToWindow(Offset(0f, coordinates.size.height.toFloat())),
      coordinates.localToWindow(
        Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
      ),
    )
  val full =
    Rect(
      left = corners.minOf { it.x },
      top = corners.minOf { it.y },
      right = corners.maxOf { it.x },
      bottom = corners.maxOf { it.y },
    )
  positionCatalogRuntimeSurface(
    surfaceId,
    full.left / scale,
    full.top / scale,
    full.width / scale,
    full.height / scale,
    visible.left / scale,
    visible.top / scale,
    visible.right / scale,
    visible.bottom / scale,
    positionVersion,
  )
}

private fun UiBuilderInspectionSnapshot.inEditorCoordinates(
  coordinates: LayoutCoordinates
): UiBuilderInspectionSnapshot {
  val origin = coordinates.positionInRoot()
  val unit = coordinates.localToRoot(Offset(1f, 1f)) - coordinates.localToRoot(Offset.Zero)
  fun UiBuilderPixelBounds.shifted() =
    copy(
      x = origin.x + x * unit.x,
      y = origin.y + y * unit.y,
      width = width * unit.x,
      height = height * unit.y,
    )
  return copy(
    nodes = nodes.map { node -> node.copy(bounds = node.bounds?.shifted()) },
    slots = slots.map { slot -> slot.copy(bounds = slot.bounds?.shifted()) },
  )
}

@JsFun(
  """() => {
    const next = (globalThis.__uiBuilderCatalogRuntimeSurfaceSequence || 0) + 1;
    globalThis.__uiBuilderCatalogRuntimeSurfaceSequence = next;
    return 'ui-builder-catalog-runtime-' + next;
  }"""
)
private external fun nextCatalogRuntimeSurfaceId(): String

private fun mountCatalogRuntimeSurface(surfaceId: String): Unit =
  js(
    """(function () {
      if (document.getElementById(surfaceId)) return;
      const host = document.createElement('div');
      host.id = surfaceId;
      const app = document.getElementById('composeApp');
      if (app) {
        app.style.position = app.style.position || 'relative';
        app.style.zIndex = '1';
      }
      host.style.cssText = 'position:fixed;overflow:hidden;z-index:0;pointer-events:none';
      document.body.append(host);
    })()"""
  )

private fun positionCatalogRuntimeSurface(
  surfaceId: String,
  left: Float,
  top: Float,
  width: Float,
  height: Float,
  visibleLeft: Float,
  visibleTop: Float,
  visibleRight: Float,
  visibleBottom: Float,
  positionVersion: Int,
): Unit =
  js(
    """(function () {
      const host = document.getElementById(surfaceId);
      if (!host) return;
      host.style.left = left + 'px';
      host.style.top = top + 'px';
      host.style.width = Math.max(0, width) + 'px';
      host.style.height = Math.max(0, height) + 'px';
      const insetTop = Math.max(0, visibleTop - top);
      const insetRight = Math.max(0, left + width - visibleRight);
      const insetBottom = Math.max(0, top + height - visibleBottom);
      const insetLeft = Math.max(0, visibleLeft - left);
      host.style.clipPath = 'inset(' + insetTop + 'px ' + insetRight + 'px ' +
        insetBottom + 'px ' + insetLeft + 'px)';
      const controller = host.__uiBuilderCatalogRuntime;
      if (controller?.frame) {
        controller.frame.style.transform = 'scale(' +
          (Math.max(0, width) / controller.frameWidth) + ',' +
          (Math.max(0, height) / controller.frameHeight) + ')';
      }
    })()"""
  )

private fun updateCatalogRuntimeSurface(
  surfaceId: String,
  runtimeId: String,
  documentJson: String,
  widthDp: Float,
  heightDp: Float,
  density: Float,
  mode: String,
  selectedNodeId: String,
  selectionEnabled: Boolean,
  editorOverlayOpen: Boolean,
): Unit =
  js(
    """(function () {
      const host = document.getElementById(surfaceId);
      if (!host) return;
      // A device frame is interactive, so it sits above the canvas (z 20) and takes the pointer —
      // except while an editor menu or dialog is open, which Compose draws inside that canvas.
      // Then it drops beneath (z 0, no pointer): the hole the canvas punched keeps it visible, and
      // the menu is painted over it. Applied before the early returns so a re-stack alone works.
      const raised = mode === 'device' && !editorOverlayOpen;
      host.style.pointerEvents = raised ? 'auto' : 'none';
      host.style.zIndex = raised ? '20' : '0';
      if (!runtimeId || !/^[A-Za-z0-9._-]+$/.test(runtimeId) ||
          runtimeId === 'latest' || runtimeId === 'current') {
        host.textContent = 'This design has no compatible pinned catalog runtime.';
        return;
      }
      const render = {
        documentJson, widthDp, heightDp, density, mode, selectedNodeId, selectionEnabled
      };
      // What the frame is BUILT for: its native pixel size and surface mode are fixed when it is
      // created. The document is not part of it. An edit used to change this key, so every edit
      // tore the frame down and cold-booted the catalog's whole Wasm runtime again -- a blank pane
      // for as long as that took -- when a live frame takes the new document in one message.
      // The device pixel ratio too: the frame's CSS size is derived from it, and browser zoom
      // changes it.
      const pixelRatio = globalThis.devicePixelRatio || 1;
      const compositionKey = widthDp + '|' + heightDp + '|' + density + '|' + mode + '|' + pixelRatio;
      let controller = host.__uiBuilderCatalogRuntime;
      if (controller && !controller.disposed && controller.runtimeId === runtimeId &&
          controller.compositionKey === compositionKey) {
        controller.render = render;
        controller.renderLatest();
        controller.drawOverlay();
        return;
      }
      if (controller) controller.dispose();
      host.replaceChildren();
      const root = '/ui-builder/runtime/' + encodeURIComponent(runtimeId) + '/';
      const frame = document.createElement('iframe');
      frame.title = 'Pinned catalog design renderer';
      frame.sandbox = 'allow-scripts';
      const nativeWidth = Math.max(1, widthDp * density);
      const nativeHeight = Math.max(1, heightDp * density);
      // The runtime lays out in DEVICE pixels, like any Compose canvas: a frame whose CSS size was
      // the native size drew the design into 1/devicePixelRatio of itself, the top-left 38% on a
      // 2.625x phone and white beyond. Sized at native / ratio CSS pixels, its device pixels are
      // the native ones, which is also what its inspection bounds are reported in.
      const frameWidth = nativeWidth / pixelRatio;
      const frameHeight = nativeHeight / pixelRatio;
      frame.style.cssText = 'position:absolute;top:0;left:0;width:' + frameWidth +
        'px;height:' + frameHeight + 'px;border:0;background:transparent;transform-origin:top left;' +
        'transform:scale(' + (host.clientWidth / frameWidth) + ',' +
        (host.clientHeight / frameHeight) + ')';
      const overlay = document.createElement('div');
      overlay.setAttribute('aria-label', 'Editor selection overlay');
      overlay.style.cssText = 'position:absolute;inset:0;z-index:1;overflow:hidden';
      host.append(frame, overlay);
      let sequence = 0;
      let initialized = false;
      let initializing = null;
      let manifest = null;
      let lastRenderKey = '';
      let latestRenderRequestId = null;
      const pending = new Map();
      controller = {
        runtimeId,
        compositionKey,
        frame,
        frameWidth,
        frameHeight,
        render,
        disposed: false,
        request(type, payload) {
          if (!manifest || !frame.contentWindow) return;
          const requestId = surfaceId + '-' + (++sequence);
          const body = type === 'renderDocument' ? payload.document : null;
          // A newer render replaces one still waiting: the frame may conflate them and answer only
          // the last, and a long-lived frame must not keep every superseded request.
          if (type === 'renderDocument') {
            for (const [id, entry] of pending) if (entry.type === 'renderDocument') pending.delete(id);
          }
          pending.set(requestId, {
            type,
            documentId: body?.id,
            documentRevision: body?.revision,
          });
          frame.contentWindow.postMessage(JSON.stringify({
            schema: 'compose-ui-builder-renderer/v' + manifest.protocolVersion,
            protocolVersion: manifest.protocolVersion,
            runtimeId,
            requestId,
            type,
            payload: payload || {},
          }), '*');
          return requestId;
        },
        renderLatest() {
          if (!initialized || this.disposed) return;
          const current = this.render;
          const key = current.documentJson + '|' + current.widthDp + '|' +
            current.heightDp + '|' + current.density;
          if (key === lastRenderKey) return;
          lastRenderKey = key;
          delete host.__uiBuilderInspection;
          delete host.__uiBuilderInspectionJson;
          const parsed = JSON.parse(current.documentJson);
          const payload = manifest.protocolVersion === 1 ? { document: parsed } : {
            document: parsed,
            surface: {
              mode: current.mode,
              widthDp: Math.max(1, current.widthDp),
              heightDp: Math.max(1, current.heightDp),
              density: Math.max(0.01, current.density),
              surfaceId,
            },
          };
          latestRenderRequestId = this.request('renderDocument', payload);
        },
        drawOverlay() {
          overlay.replaceChildren();
          overlay.style.pointerEvents = 'none';
          const inspection = host.__uiBuilderInspection;
          if (!inspection || !this.render.selectionEnabled) return;
          const scaleX = host.clientWidth / (this.render.widthDp * this.render.density);
          const scaleY = host.clientHeight / (this.render.heightDp * this.render.density);
          for (const node of inspection.nodes) {
            if (!node.bounds || node.nodeId !== this.render.selectedNodeId) continue;
            const marker = document.createElement('div');
            marker.dataset.nodeId = node.nodeId;
            marker.style.cssText = 'position:absolute;box-sizing:border-box;border:2px solid #6750a4;pointer-events:none';
            marker.style.left = (node.bounds.x * scaleX) + 'px';
            marker.style.top = (node.bounds.y * scaleY) + 'px';
            marker.style.width = (node.bounds.width * scaleX) + 'px';
            marker.style.height = (node.bounds.height * scaleY) + 'px';
            overlay.append(marker);
          }
        },
        dispose() {
          this.disposed = true;
          if (initializing !== null) clearInterval(initializing);
          removeEventListener('message', onMessage);
          pending.clear();
          frame.remove();
          overlay.remove();
        },
      };
      host.__uiBuilderCatalogRuntime = controller;
      const finiteBound = (value) => Number.isFinite(value) && Math.abs(value) <= 1000000;
      const validBounds = (bounds) => bounds == null || (
        finiteBound(bounds.x) && finiteBound(bounds.y) &&
        finiteBound(bounds.width) && finiteBound(bounds.height) &&
        bounds.width >= 0 && bounds.height >= 0
      );
      const validInspection = (inspection, expected) => {
        if (!inspection || inspection.schema !== 'compose-ui-builder-inspection/v1' ||
            inspection.documentId !== expected.documentId ||
            inspection.documentRevision !== expected.documentRevision ||
            inspection.coordinateSpace !== 'root-render-pixels' ||
            inspection.coordinatePrecision !== '1/64px' ||
            !Array.isArray(inspection.nodes) || inspection.nodes.length > 10000 ||
            !Array.isArray(inspection.slots) || inspection.slots.length > 20000) return false;
        return inspection.nodes.every((node) => node && typeof node.nodeId === 'string' &&
          node.nodeId && validBounds(node.bounds));
      };
      const onMessage = (event) => {
        if (controller.disposed || event.source !== frame.contentWindow ||
            event.origin !== 'null' || typeof event.data !== 'string') return;
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        if (!manifest || message.schema !== 'compose-ui-builder-renderer/v' + manifest.protocolVersion ||
            message.protocolVersion !== manifest.protocolVersion ||
            message.runtimeId !== runtimeId || !pending.has(message.requestId)) return;
        const expected = pending.get(message.requestId);
        const expectedType = expected.type === 'initialize' ? 'initialized' : 'rendered';
        if (message.type !== 'error' && message.type !== expectedType) return;
        if (message.type === 'rendered' &&
            !validInspection(message.payload?.inspection, expected)) return;
        pending.delete(message.requestId);
        if (message.type === 'initialized') {
          initialized = true;
          if (initializing !== null) clearInterval(initializing);
          controller.renderLatest();
        } else if (message.type === 'rendered') {
          // Edits now reach one live frame back to back; an answer for a revision already replaced
          // must not put its selection bounds over the newer drawing.
          if (message.requestId !== latestRenderRequestId) return;
          host.__uiBuilderInspection = message.payload.inspection;
          host.__uiBuilderInspectionJson = JSON.stringify(message.payload.inspection);
          controller.drawOverlay();
        } else if (message.type === 'error') {
          host.__uiBuilderRuntimeError = message.payload;
        }
      };
      addEventListener('message', onMessage);
      fetch(root + 'runtime-manifest.json', {
        credentials: 'same-origin', headers: { Accept: 'application/json' },
      }).then((response) => {
        if (!response.ok) throw new Error('runtime manifest HTTP ' + response.status);
        return response.json();
      }).then((loaded) => {
        if (controller.disposed) return;
        if (!['compose-ui-builder-runtime/v1', 'compose-ui-builder-runtime/v2'].includes(loaded.schema) ||
            loaded.runtimeId !== runtimeId || ![1, 2].includes(loaded.protocolVersion) ||
            typeof loaded.entrypoint !== 'string' ||
            !/^[A-Za-z0-9._/-]+$/.test(loaded.entrypoint) ||
            loaded.entrypoint.split('/').some((part) => !part || part === '.' || part === '..')) {
          throw new Error('pinned runtime manifest does not match the editor protocol');
        }
        manifest = loaded;
        frame.addEventListener('load', () => {
          controller.request('initialize', {});
          initializing = setInterval(() => {
            if (!initialized) controller.request('initialize', {});
          }, 250);
        }, { once: true });
        frame.src = root + loaded.entrypoint;
      }).catch((error) => {
        if (controller.disposed) return;
        controller.dispose();
        delete host.__uiBuilderCatalogRuntime;
        host.replaceChildren();
        host.textContent = 'Pinned catalog runtime unavailable: ' + error.message;
        host.__uiBuilderRuntimeError = error.message;
      });
    })()"""
  )

private fun readCatalogRuntimeInspection(surfaceId: String): String =
  js("document.getElementById(surfaceId)?.__uiBuilderInspectionJson || ''")

private fun disposeCatalogRuntimeSurface(surfaceId: String): Unit =
  js(
    """(function () {
      const host = document.getElementById(surfaceId);
      if (!host) return;
      host.__uiBuilderCatalogRuntime?.dispose();
      delete host.__uiBuilderCatalogRuntime;
      delete host.__uiBuilderInspectionJson;
      delete host.__uiBuilderInspection;
      host.remove();
    })()"""
  )

/**
 * Minimal editor-side vertical slice for the isolated runtime. The iframe owns design pixels; the
 * absolutely positioned sibling owns selection geometry and never participates in renderer layout.
 * Semantic actions target inspected Compose nodes by stable id; the sibling overlay remains
 * pointer-inert and outside the renderer's Compose tree.
 */
private fun mountSandboxRenderer(runtimeId: String, documentJson: String): Unit =
  js(
    """(async function () {
       const root = '/ui-builder/runtime/' + encodeURIComponent(runtimeId) + '/';
      const response = await fetch(root + 'runtime-manifest.json', {
        credentials: 'same-origin', headers: { Accept: 'application/json' }
      });
      if (!response.ok) throw new Error('runtime manifest HTTP ' + response.status);
      const manifest = await response.json();
       if (!['compose-ui-builder-runtime/v1', 'compose-ui-builder-runtime/v2'].includes(manifest.schema) ||
           manifest.runtimeId !== runtimeId || ![1, 2].includes(manifest.protocolVersion) ||
          typeof manifest.entrypoint !== 'string' ||
          !/^[A-Za-z0-9._/-]+$/.test(manifest.entrypoint) ||
          manifest.entrypoint.split('/').some((part) => !part || part === '.' || part === '..')) {
         throw new Error('pinned runtime manifest does not match the editor protocol');
       }
       const protocolVersion = manifest.protocolVersion;
       const schema = 'compose-ui-builder-renderer/v' + protocolVersion;
       const renderedDocument = JSON.parse(documentJson);

      const shell = document.getElementById('composeApp');
      shell.replaceChildren();
      shell.style.position = 'relative';
      const frame = document.createElement('iframe');
      frame.id = 'ui-builder-renderer-frame';
      frame.title = 'Native Compose design renderer';
      frame.sandbox = 'allow-scripts';
      frame.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;border:0;background:transparent';
      const overlay = document.createElement('div');
      overlay.id = 'ui-builder-renderer-overlay';
      overlay.setAttribute('aria-hidden', 'true');
      overlay.style.cssText = 'position:absolute;inset:0;pointer-events:none;overflow:hidden';
      shell.append(frame, overlay);

      let sequence = 0;
      let initialized = false;
       let initializeTimer = null;
       let activeSurface = null;
       const pending = new Map();
      const responses = new Map();
      const rendererGeometry = () => {
         const frameRect = frame.getBoundingClientRect();
         const shellRect = shell.getBoundingClientRect();
         const rendererWidth = activeSurface
           ? activeSurface.widthDp * activeSurface.density : frame.clientWidth;
         const rendererHeight = activeSurface
           ? activeSurface.heightDp * activeSurface.density : frame.clientHeight;
         return {
           offsetX: frameRect.left - shellRect.left,
           offsetY: frameRect.top - shellRect.top,
           scaleX: frameRect.width / rendererWidth,
           scaleY: frameRect.height / rendererHeight,
        };
      };
      const rendererToShell = (x, y) => {
        const geometry = rendererGeometry();
        return {
          x: geometry.offsetX + x * geometry.scaleX,
          y: geometry.offsetY + y * geometry.scaleY,
        };
      };
       const request = (type, payload) => {
        const requestId = 'browser-' + (++sequence);
        const document = type === 'renderDocument' ? payload?.document : null;
        const action = type === 'dispatchAction' ? payload : null;
        pending.set(requestId, {
          type,
          documentId: document?.id ?? action?.documentId,
          documentRevision: document?.revision ?? action?.documentRevision,
        });
        frame.contentWindow.postMessage(JSON.stringify({
          schema, protocolVersion, runtimeId, requestId, type, payload: payload || {}
        }), '*'); // opaque sandbox origins require `*`; source and response origin are checked.
         return requestId;
       };
       const renderPayload = () => {
         if (protocolVersion === 1) return { document: renderedDocument };
         const authoredDensity = Number(renderedDocument.environment?.density);
         const density = Number.isFinite(authoredDensity) && authoredDensity > 0
           ? authoredDensity : globalThis.devicePixelRatio || 1;
          activeSurface = {
            // This transport fixture proves semantic scrolling, so it is a device surface. The
            // editor's separate extent smoke uses authoring-unrolled through CatalogRuntimeCanvas.
            mode: 'device',
           widthDp: Math.max(1, frame.clientWidth),
           heightDp: Math.max(1, frame.clientHeight),
           density,
           surfaceId: 'editor',
         };
         return {
           document: renderedDocument,
           surface: activeSurface,
         };
       };
      const finiteBound = (value) => Number.isFinite(value) && Math.abs(value) <= 1000000;
      const validBounds = (bounds) => bounds == null || (
        finiteBound(bounds.x) && finiteBound(bounds.y) &&
        finiteBound(bounds.width) && finiteBound(bounds.height) &&
        bounds.width >= 0 && bounds.height >= 0
      );
      const validInspection = (inspection, expected) => {
        if (!inspection || inspection.schema !== 'compose-ui-builder-inspection/v1' ||
            inspection.documentId !== expected.documentId ||
            inspection.documentRevision !== expected.documentRevision ||
            inspection.coordinateSpace !== 'root-render-pixels' ||
            inspection.coordinatePrecision !== '1/64px' ||
            !Array.isArray(inspection.nodes) || inspection.nodes.length === 0 ||
            inspection.nodes.length > 10000 || !Array.isArray(inspection.slots) ||
            inspection.slots.length > 20000 || !inspection.generation ||
            inspection.generation.key !== inspection.documentId + '@' + inspection.documentRevision ||
            !Number.isInteger(inspection.generation.stabilityFrames) ||
            inspection.generation.stabilityFrames < 1 || inspection.generation.stabilityFrames > 120)
          return false;
        for (const field of ['expectedAuthoredNodeIds', 'expectedAuthoredTextNodeIds',
                             'measuredNodeIds', 'measuredTextNodeIds']) {
          if (!Array.isArray(inspection.generation[field]) ||
              inspection.generation[field].length > 10000) return false;
        }
        const ids = new Set();
        for (const node of inspection.nodes) {
          if (!node || typeof node.nodeId !== 'string' || !node.nodeId ||
              node.nodeId.length > 512 || ids.has(node.nodeId) || !validBounds(node.bounds) ||
              !node.semantics || !Array.isArray(node.semantics.actions) ||
              node.semantics.actions.length > 64) return false;
          if (node.text && (!Number.isInteger(node.text.lineCount) || node.text.lineCount < 0 ||
              !finiteBound(node.text.firstBaselineY) || !finiteBound(node.text.lastBaselineY)))
            return false;
          ids.add(node.nodeId);
        }
        return inspection.slots.every((slot) => slot &&
          typeof slot.parentNodeId === 'string' && slot.parentNodeId &&
          typeof slot.slotName === 'string' &&
          Array.isArray(slot.childNodeIds) && slot.childNodeIds.length <= 10000 &&
          Array.isArray(slot.measuredChildNodeIds) && slot.measuredChildNodeIds.length <= 10000 &&
          validBounds(slot.bounds));
      };
      const drawOverlay = (inspection) => {
        overlay.replaceChildren();
        const geometry = rendererGeometry();
        for (const node of inspection.nodes || []) {
          if (!node.bounds) continue;
          const marker = document.createElement('div');
          marker.dataset.nodeId = node.nodeId;
          marker.style.cssText = 'position:absolute;box-sizing:border-box;border:1px solid transparent';
          const topLeft = rendererToShell(node.bounds.x, node.bounds.y);
          marker.style.left = topLeft.x + 'px';
          marker.style.top = topLeft.y + 'px';
          marker.style.width = (node.bounds.width * geometry.scaleX) + 'px';
          marker.style.height = (node.bounds.height * geometry.scaleY) + 'px';
          overlay.append(marker);
        }
        globalThis.__uiBuilderSandboxInspection = inspection;
        globalThis.__uiBuilderSandboxOverlayCount = overlay.childElementCount;
      };
      addEventListener('message', (event) => {
        if (event.source !== frame.contentWindow || event.origin !== 'null' || typeof event.data !== 'string') return;
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        if (message.schema !== schema || message.protocolVersion !== protocolVersion ||
            message.runtimeId !== runtimeId || !pending.has(message.requestId)) return;
        const expected = pending.get(message.requestId);
        const expectedResponse = expected.type === 'initialize' ? 'initialized' :
          expected.type === 'renderDocument' ? 'rendered' :
          expected.type === 'dispatchAction' ? 'actionDispatched' : null;
        if (message.type !== 'error' && message.type !== expectedResponse) return;
        if ((message.type === 'rendered' || message.type === 'actionDispatched') &&
            !validInspection(message.payload?.inspection, expected)) return;
        pending.delete(message.requestId);
        responses.set(message.requestId, message);
        if (message.type === 'initialized') {
          if (initialized) return;
          initialized = true;
          if (initializeTimer !== null) clearInterval(initializeTimer);
           request('renderDocument', renderPayload());
        } else if (message.type === 'rendered') {
          drawOverlay(message.payload.inspection);
          document.documentElement.dataset.uiBuilderSandboxReady = 'true';
        } else if (message.type === 'actionDispatched') {
          drawOverlay(message.payload.inspection);
        } else if (message.type === 'error') {
          globalThis.__uiBuilderSandboxLastError = message.payload;
        }
      });
      frame.addEventListener('load', () => {
        request('initialize');
        initializeTimer = setInterval(() => {
          if (!initialized) request('initialize');
        }, 250);
      });
      frame.src = root + manifest.entrypoint;
      globalThis.__uiBuilderSandboxDispatchAction = (payload) => request('dispatchAction', payload);
      globalThis.__uiBuilderSandboxResponse = (requestId) => responses.get(requestId) || null;
      globalThis.__uiBuilderSandboxActivateNode = (nodeId) => {
        const inspection = globalThis.__uiBuilderSandboxInspection;
        return request('dispatchAction', {
          documentId: inspection.documentId,
          documentRevision: inspection.documentRevision,
          nodeId,
          kind: 'activate',
        });
      };
      globalThis.__uiBuilderSandboxScrollNodeBy = (nodeId, deltaY) => {
        const inspection = globalThis.__uiBuilderSandboxInspection;
        return request('dispatchAction', {
          documentId: inspection.documentId,
          documentRevision: inspection.documentRevision,
          nodeId,
          kind: 'scrollBy',
          deltaX: 0,
          deltaY,
        });
      };
    })()"""
  )

internal data class LiveSessionConfig(
  val catalogSystemId: String,
  val designId: String,
  /**
   * What in the design this URL means: a revision, a node, a thread.
   *
   * Parsed once, here, by [parseDesignUrlSelectors] in common code rather than by a `@JsFun` of its
   * own, so the grammar a link is written in has one definition and a test that does not need a
   * browser.
   */
  val selectors: DesignUrlSelectors,
  val actorId: String,
  val clientId: String,
  val httpEndpoint: String,
  val webSocketEndpoint: String,
  val startWithNewDesign: Boolean,
  val operationIdPrefix: String,
  val displayName: String,
  val colorArgbHex: String,
  /**
   * Whether this design lives in the browser rather than on the server.
   *
   * `?storage=local`, a query rather than a path segment, because it says *where the design is
   * kept* and not *which design* — the same distinction that keeps `actor` and `token` in the
   * query. The server never sees it: the app shell is served for the same catalog-scoped path
   * either way, and the page decides what to do with it.
   */
  val localStorage: Boolean,
)

internal suspend fun fetchCatalogRecovery(designId: String): CatalogUpgradePreviewV1 =
  catalogRecoveryJson
    .decodeFromString<BrowserCatalogRecoveryPayload>(
      fetchText("/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/catalog-recovery")
    )
    .preview

private val catalogRecoveryJson = Json { ignoreUnknownKeys = true }

@JsFun("() => window.location.reload()") internal external fun reloadBrowserPage()

@JsFun(
  """(revision, revisionPinned, nodeId, threadId, inspectorMode) => {
    globalThis.__uiBuilderDesignSelectors = {
      revision, revisionPinned, nodeId, threadId, inspectorMode
    };
    const dataset = document.documentElement.dataset;
    dataset.uiBuilderSelectedNode = nodeId;
    dataset.uiBuilderSelectedThread = threadId;
    dataset.uiBuilderInspectorMode = inspectorMode;
    dataset.uiBuilderPinnedRevision = revisionPinned ? revision : '';
  }"""
)
internal external fun publishDesignSelectors(
  revision: String,
  revisionPinned: Boolean,
  nodeId: String,
  threadId: String,
  inspectorMode: String,
)

/**
 * One native render of a design: the host compiles it and draws it with real Compose.
 *
 * Decoding happens here rather than in the editor because `wasmJs` and the JVM decode differently
 * and neither belongs in an editor composable — the editor takes an [ImageBitmap] and knows nothing
 * about base64 or HTTP.
 *
 * The three outcomes are kept apart on purpose. A 422 is the generator refusing the design and its
 * reasons are actionable; any other non-200 is this host failing, which is a different sentence;
 * and a 200 with no frame means the compile lane answered without one, which the editor says
 * plainly rather than showing an empty box.
 */
internal suspend fun requestNativeRender(
  designId: String,
  revision: Long? = null,
  hostShape: WearWidgetHostShape = WearWidgetHostShape.Default,
): UiBuilderNativeRender {
  val response =
    BrowserUiBuilderHttpTransport()
      .post(
        UiBuilderHttpRequest(
          // The revision rides in the query, as it does on the export routes: absent means the
          // current committed revision, which is what an unpinned editor asks for.
          endpoint =
            "/api/ui-builder/v1/designs/$designId/native-preview" +
              (revision?.let { "?revision=$it" } ?: ""),
          contentType = "application/json",
          // The host container to frame a widget in. In the body rather than the query beside the
          // revision, because it says what to draw rather than which version to read; a host that
          // predates the field ignores it and draws the squircle, which is what it drew before.
          body = "{\"hostShape\":\"${hostShape.id}\"}",
        )
      )
  if (response.statusCode == 422) {
    val refusal =
      nativePreviewJson.decodeFromString(NativePreviewRefusal.serializer(), response.body)
    return UiBuilderNativeRender(refusals = refusal.reasons)
  }
  if (response.statusCode != 200) {
    return UiBuilderNativeRender(
      failure = "the host answered ${response.statusCode} to a native render request"
    )
  }
  val result = nativePreviewJson.decodeFromString(NativePreviewResult.serializer(), response.body)
  result.compileError?.let {
    return UiBuilderNativeRender(failure = it)
  }
  val encoded = result.imageBase64 ?: return UiBuilderNativeRender()
  // The compile lane's `image` is a `data:image/png;base64,…` URI, because that is what the
  // playground page puts straight into an `<img src>`. Strict Base64 rejects the prefix, so a frame
  // that arrived intact used to surface as a decode failure. Tolerant of both spellings rather than
  // pinned to one: the field is named for its payload, and the prefix is the wrapper.
  val payload = encoded.substringAfterLast("base64,")
  return UiBuilderNativeRender(
    image = Image.makeFromEncoded(Base64.decode(payload)).toComposeImageBitmap(),
    nodeBounds =
      result.nodeBounds.mapValues { (_, box) ->
        UiBuilderNativeNodeBounds(x = box.x, y = box.y, width = box.width, height = box.height)
      },
    // Where the same compile can be *watched*. Absent on a host with no Stage-2 redemption, and on
    // a design whose mode has no daemon backend here — in both cases the pane keeps the still.
    live =
      result.live?.let { UiBuilderNativeLive(sessionId = it.sessionId, previewId = it.previewId) },
  )
}

/** Tolerant: a field added to the native-render payload must not blank the pane. */
private val nativePreviewJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class NativePreviewResult(
  val imageBase64: String? = null,
  val taggedNodeIds: List<String> = emptyList(),
  /** Design node id → its box on the frame, in the frame's own pixels. See `nodeBounds` there. */
  val nodeBounds: Map<String, NativePreviewNodeBounds> = emptyMap(),
  val compileError: String? = null,
  /** Where to open the live stream for this render — see `NativePreviewLiveV1` on the server. */
  val live: NativePreviewLive? = null,
)

@kotlinx.serialization.Serializable
private data class NativePreviewLive(val sessionId: String, val previewId: String)

@kotlinx.serialization.Serializable
private data class NativePreviewNodeBounds(
  val x: Int = 0,
  val y: Int = 0,
  val width: Int = 0,
  val height: Int = 0,
)

@kotlinx.serialization.Serializable
private data class NativePreviewRefusal(val reasons: List<String> = emptyList())

internal suspend fun loadRemoteComposeSources(catalogSystemId: String): List<RemoteComposeSource> =
  try {
    parseRemoteComposeSources(fetchText(catalogAssetPath(catalogSystemId, "/api/previews")))
  } catch (failure: Throwable) {
    emptyList()
  }

/** Bytes rather than text: a Remote Compose document is a binary wire format. */
internal suspend fun fetchBase64(url: String, document: String? = null): String =
  suspendCancellableCoroutine { continuation ->
    fetchBase64Promise(sameOriginRequestUrl(url), document)
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString())
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(IllegalStateException(error.toString()))
        }
        null
      }
  }

@JsFun(
  """(url, document) => fetch(url, document == null ? undefined : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: document,
  }).then((response) => {
    if (!response.ok) return response.text().then((body) => {
      throw new Error('HTTP ' + response.status + (body ? ': ' + body : ''));
    });
    return response.arrayBuffer();
  }).then((buffer) => {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    // Chunked: `String.fromCharCode(...bytes)` spreads every byte as an argument, and a document of
    // any size overflows the call stack.
    for (let offset = 0; offset < bytes.length; offset += 8192) {
      binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
    }
    return btoa(binary);
  })"""
)
private external fun fetchBase64Promise(url: String, document: String?): Promise<JsString>

internal suspend fun fetchText(url: String): String = suspendCancellableCoroutine { continuation ->
  fetchTextPromise(sameOriginRequestUrl(url))
    .then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
    .catch { error ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(error.toString()))
      }
      null
    }
}

@JsFun(
  """(url) => fetch(url).then((response) => {
    if (!response.ok) throw new Error('HTTP ' + response.status);
    return response.text();
  })"""
)
private external fun fetchTextPromise(url: String): Promise<JsString>

@JsFun("() => new URLSearchParams(globalThis.location.search).get('mode') || 'interactive-editor'")
private external fun captureMode(): String

@JsFun(
  """() => {
    const params = new URLSearchParams(globalThis.location.search);
    return params.get('session') === 'live' || !params.has('mode');
  }"""
)
private external fun liveSessionEnabled(): Boolean

/**
 * Everything a design kept in this browser needs, assembled once per session.
 *
 * The three pieces are deliberately separate objects. [store] is the browser's keys and knows
 * nothing about catalogs; [catalogs] is network-first with the last successful answer kept, which
 * is what lets an offline reload open a design at all; [service] is the reducer above both,
 * answering the same v1 requests the server answers. [text] is the same network-first rule for the
 * two static files a local session still needs — the device presets and the seed fixture.
 */
internal class BrowserLocalSession(config: LiveSessionConfig) {
  private val storage = BrowserLocalDesignStorage()
  val store: LocalDesignStore = LocalDesignStore(storage)
  val catalogs: CachingLocalCatalogSource =
    CachingLocalCatalogSource(
      storage,
      LocalCatalogSource {
        loadLiveCatalogs(
          UiBuilderProtocolHttpClient(
            actorId = config.actorId,
            endpoint = config.httpEndpoint,
            transport = BrowserUiBuilderHttpTransport(),
            requestIds = MonotonicUiBuilderRequestIds("${config.clientId}-catalog"),
          )
        )
      },
    )
  val text: CachedLocalText = CachedLocalText(storage)
  val service: LocalUiBuilderService =
    LocalUiBuilderService(store, catalogs, clock = ::browserNowMillis)

  /** True once this session has fallen back to what the browser stored, which is "offline". */
  val offline: Boolean
    get() = catalogs.servedFromStorage
}

internal fun liveSessionConfig(serverActorId: String?): LiveSessionConfig {
  val catalogSystemId = liveConfigValue("catalog", uiBuilderCatalogFromPath())
  val defaultDesignId =
    if (catalogSystemId == "m3-catalog") "jetcaster-discover"
    else "$catalogSystemId-jetcaster-discover"
  // `/ui-builder/<designId>` is the canonical form. The `?designId=` query still works
  // — bookmarks and automation written against it must not break — and the path wins where both
  // are present. Neither creates anything: a GET opens a design, and bringing one into existence
  // is the `POST` the New design form submits, or a `PUT` of the design's own API resource.
  val pathDesignId = uiBuilderDesignFromPath()
  val designNamedInPath = pathDesignId.isNotEmpty()
  return LiveSessionConfig(
      catalogSystemId = catalogSystemId,
      selectors = parseDesignUrlSelectors(locationSearch(), locationHash()),
      designId =
        if (designNamedInPath) pathDesignId else liveConfigValue("designId", defaultDesignId),
      actorId = liveConfigValue("actor", serverActorId ?: "browser-user"),
      clientId = liveConfigValue("clientId", "browser-editor"),
      httpEndpoint = liveConfigValue("endpoint", "/api/ui-builder/v1/requests"),
      webSocketEndpoint =
        liveConfigValue(
          "updatesEndpoint",
          "/api/ui-builder/v1/designs/{designId}/updates",
        ),
      startWithNewDesign = !designNamedInPath && !liveConfigPresent("designId"),
      operationIdPrefix = "${liveConfigValue("clientId", "browser-editor")}-${livePageNonce()}",
      displayName =
        liveConfigValue("displayName", serverActorId?.substringAfterLast(':') ?: "Browser user"),
      colorArgbHex = liveConfigValue("color", "#FF6574CD"),
      localStorage = localDesignStorageRequested(),
    )
    .also {
      require(Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.catalogSystemId)) {
        "live catalog must be a safe catalog id"
      }
      require(it.designId.isNotBlank()) { "live designId must not be blank" }
      require(!designNamedInPath || Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.designId)) {
        "a design named in the path must be path-safe"
      }
      require(it.actorId.isNotBlank()) { "live actor must not be blank" }
      require(it.clientId.isNotBlank()) { "live clientId must not be blank" }
      require(Regex("#[0-9A-Fa-f]{8}").matches(it.colorArgbHex)) { "live color must be #AARRGGBB" }
    }
}

/**
 * The Screen inspector's device frames, read from the server.
 *
 * The server derives them from `DeviceDimensions`, the JVM-only catalog the render lane resolves
 * against; `wasmJs` cannot depend on it, which is exactly why this crosses the wire instead of
 * being a constant in `:ui-builder`. A failure is not fatal — the inspector falls back to the raw
 * width/height/density fields, which is where it was before the menu existed.
 */
internal suspend fun loadDevicePresets(cache: CachedLocalText?): List<UiBuilderDevicePreset> =
  try {
    devicePresetJson
      .decodeFromString(
        DevicePresetsPayload.serializer(),
        cache?.text(DEVICE_PRESETS_PATH) { fetchText(it) } ?: fetchText(DEVICE_PRESETS_PATH),
      )
      .presets
      .map {
        UiBuilderDevicePreset(
          id = it.id,
          label = it.label,
          group = it.group,
          widthDp = it.widthDp,
          heightDp = it.heightDp,
          density = it.density,
        )
      }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    emptyList()
  }

/**
 * The actor id the server authenticated this page as, or `null` when it will not say.
 *
 * Not fatal on its own: the caller keeps the historical default so an unauthenticated page still
 * renders and reports the server's own error, rather than failing to mount at all.
 */
internal suspend fun resolveServerActorId(): String? =
  try {
    identityJson
      .decodeFromString(IdentityPayload.serializer(), fetchText(IDENTITY_PATH))
      .actorId
      .takeIf { it.isNotBlank() }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (failure: Throwable) {
    // `Throwable`, not `Exception`, and the difference is the whole point of this branch: a
    // browser-level failure — an insecure origin refusing something, a `fetch` rejected by the
    // engine rather than the server — arrives as a Kotlin/Wasm `JsException`, which extends
    // `Throwable` and not `Exception`. Catching `Exception` let it past this guard and out of the
    // coroutine, where it took the composition with it: a blank editor on `http://<host>:8723/`
    // that worked on `http://127.0.0.1:8723/`, with nothing in the console but the coroutine
    // wrapper. The endpoint is best-effort by design, so a failure here is `null` like any other.
    println("compose-ui-builder: could not resolve the server actor: $failure")
    null
  }

/**
 * How long a settings change waits before it is stored.
 *
 * Long enough that dragging a slider from one end to the other is one request, short enough that
 * closing the tab straight after a nudge keeps it. The pictures do not go through this timer twice
 * — an unchanged picture takes the settings route, which carries no bytes.
 */
internal const val REFERENCE_SAVE_DEBOUNCE_MILLIS = 600L

private const val IDENTITY_PATH = "/api/ui-builder/v1/identity"

/** Tolerant for the same reason as the presets: a new identity field must not blank the actor. */
private val identityJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable private data class IdentityPayload(val actorId: String = "")

/**
 * Encoded, because a design id is not guaranteed to be URL-safe.
 *
 * The legacy query form only requires an id to be non-blank, so one carrying a `#` would request
 * the report for the part before it — the rest becomes a fragment the server never sees — and one
 * carrying a `/` would address a different route entirely. Either way the broad catch below turns
 * the wrong answer into an empty report, which is silence rather than a visible failure.
 */
private fun componentDriftPath(designId: String, revision: Long?): String =
  "/api/ui-builder/v1/designs/${encodeUrlComponent(designId)}/component-drift" +
    // The revision on screen, not the head. A design opened at `?revision=` shows the components
    // that revision held, and the report has to be about those or it answers a question nobody
    // asked — silently missing a component the pinned revision imported and head no longer has.
    (revision?.let { "?revision=$it" } ?: "")

/** Tolerant like the rest: a state or field this build does not know must not blank the report. */
private val componentDriftJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class ComponentDriftPayload(val components: List<ComponentDriftWire> = emptyList())

@kotlinx.serialization.Serializable
private data class ComponentDriftWire(
  val componentKey: String = "",
  val system: String = "",
  val componentId: String = "",
  val paletteId: String = "",
  val state: String = "",
  val importedDigest: String = "",
  val currentDigest: String? = null,
)

/**
 * Whether the shared components this design imported still match the library they came from.
 *
 * A design holds the body of every component it imported, so it draws and exports the same way
 * whatever the project does afterwards — which is exactly why nothing in the document can answer
 * this and it has to be asked over the wire.
 *
 * A failure is not fatal, for the reason [loadDevicePresets]'s is not: a host that serves no
 * component library answers 404 here, and an editor that refused to open over that would be an
 * editor most hosts could not run. An unrecognised state is dropped rather than guessed at — a
 * verdict this build cannot name is one it cannot word either.
 */
internal suspend fun loadComponentDrift(
  designId: String,
  revision: Long?,
): List<ComponentDriftFinding> =
  try {
    componentDriftJson
      .decodeFromString(
        ComponentDriftPayload.serializer(),
        fetchText(componentDriftPath(designId, revision)),
      )
      .components
      .mapNotNull { row ->
        val state =
          when (row.state) {
            "unchanged" -> ComponentDriftState.UNCHANGED
            "drifted" -> ComponentDriftState.DRIFTED
            "withdrawn" -> ComponentDriftState.WITHDRAWN
            "unusable" -> ComponentDriftState.UNUSABLE
            else -> return@mapNotNull null
          }
        ComponentDriftFinding(
          componentKey = row.componentKey,
          system = row.system,
          componentId = row.componentId,
          paletteId = row.paletteId,
          state = state,
          importedDigest = row.importedDigest,
          currentDigest = row.currentDigest,
        )
      }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    emptyList()
  }

private const val DEVICE_PRESETS_PATH = "/api/ui-builder/v1/device-presets"

/** Tolerant on purpose: a server that learns a new preset field must not blank the whole menu. */
private val devicePresetJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class DevicePresetsPayload(val presets: List<DevicePresetWire> = emptyList())

@kotlinx.serialization.Serializable
private data class DevicePresetWire(
  val id: String,
  val label: String,
  val group: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
)

/**
 * The component record this host generates [catalogSystemId]'s exports from, or null.
 *
 * Null on every failure, deliberately and quietly: a host with no record for this catalog answers
 * 404, an older host has no such route at all, and an offline page reaches neither. All three mean
 * the same thing to the editor — judge the design by the record embedded in this build, exactly as
 * it did before the route existed — and none of them is worth a banner over a design that draws and
 * exports regardless.
 *
 * Unknown keys are ignored for the reason `EmbeddedComponentRecordAccess` gives: a record from a
 * newer producer should still parse, and which schema versions may be generated from is the
 * *host's* judgement, made before this is served. A record it would not generate from is not served
 * at all.
 */
internal suspend fun fetchCatalogRecord(catalogSystemId: String): ComponentRecordFile? =
  runCatching {
    catalogRecordJson.decodeFromString<ComponentRecordFile>(
      fetchText(
        "/api/ui-builder/v1/catalogs/${encodeUriComponent(catalogSystemId)}/component-record"
      )
    )
  }
  .getOrNull()

private val catalogRecordJson = Json { ignoreUnknownKeys = true }

internal suspend fun loadLiveCatalogs(
  http: UiBuilderProtocolHttpClient
): List<CatalogCapabilityV1> =
  when (val result = http.execute(ListCatalogsRequestV1)) {
    is UiBuilderHttpResult.Response -> {
      val catalogs =
        result.response as? CatalogsResponseV1 ?: error("unexpected list-catalogs response")
      catalogs.catalogs
    }
    is UiBuilderHttpResult.ServiceError -> error(result.error.message)
    is UiBuilderHttpResult.SnapshotRequired -> error(result.error.message)
  }

/** The chooser's order. Anything not named here (there is nothing today) sorts first. */
internal val NEW_DESIGN_CATALOG_ORDER = listOf("m3-catalog", "wear-m3", "remote-m3")

/**
 * Labelled by what a person is making — a phone screen, a watch screen, a RemoteCompose widget —
 * rather than by the catalog that draws it. "Material 3" and "Wear Material 3" told an M3 reader
 * the truth and everyone else nothing about which chip to press.
 */
internal fun newDesignCatalog(catalog: CatalogCapabilityV1): UiBuilderNewDesignCatalog? =
  when (catalog.benchmark.catalogSystemId) {
    "m3-catalog" ->
      UiBuilderNewDesignCatalog(
        systemId = "m3-catalog",
        label = "Android app",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "blank",
              label = "Blank screen",
              supportingText = "A Material scaffold with an empty content container.",
            ),
            UiBuilderNewDesignTemplate(
              id = UiBuilderNewDesignSeed.HELLO_TEMPLATE,
              label = "Hello sample",
              supportingText = "The same scaffold with a headline and a line of text to edit.",
            ),
          ),
      )
    "remote-m3" ->
      UiBuilderNewDesignCatalog(
        systemId = "remote-m3",
        label = "Wear widget",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "wear-widget-small",
              label = "Small widget",
              supportingText = "216×76dp host with a single content slot.",
            ),
            UiBuilderNewDesignTemplate(
              id = "wear-widget-large",
              label = "Large widget",
              supportingText = "216×124dp host with a single content slot.",
            ),
            UiBuilderNewDesignTemplate(
              id = AdaptiveWearWidget.TEMPLATE_ID,
              label = "Adaptive widget (experimental)",
              supportingText =
                "Headline, supporting and action slots, laid out for both sizes: Small drops " +
                  "the supporting line.",
            ),
          ) +
            // The two worked samples, after the empty scaffolds rather than before them: a blank
            // host is what someone starting their own widget wants, and a sample is what someone
            // asking "can this express a real one?" wants.
            WearWidgetSample.entries.map {
              UiBuilderNewDesignTemplate(
                id = it.templateId,
                label = it.label,
                supportingText = it.supportingText,
              )
            },
      )
    "wear-m3" ->
      UiBuilderNewDesignCatalog(
        systemId = "wear-m3",
        label = "Wear app",
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
              label = "Wear screen",
              supportingText =
                "A ScreenScaffold with its clock and scroll indicator, over an empty list.",
            ),
            // After the empty one, for the reason the widget samples come after the empty hosts: a
            // blank scaffold is what somebody starting their own screen wants, and the worked list
            // is what somebody asking "does this match a real Wear render?" wants.
            UiBuilderNewDesignTemplate(
              id = UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
              label = "Activity list",
              supportingText = "Six title cards under a list header, row for row the reference's.",
            ),
          ),
      )
    // A catalog this build has no templates for — one an operator enabled that the chooser has
    // never heard of. It still gets a card, named after itself, with the blank starting point the
    // server's seed gives every unknown catalog, rather than silently missing from the chooser.
    else ->
      UiBuilderNewDesignCatalog(
        systemId = catalog.benchmark.catalogSystemId,
        label =
          catalog.benchmark.catalogSystemId
            .split('-', '_', '.')
            .filter(String::isNotEmpty)
            .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) },
        platform = UiBuilderCatalogPlatform.from(catalog.statusSemantics),
        templates =
          listOf(
            UiBuilderNewDesignTemplate(
              id = "blank",
              label = "Blank screen",
              supportingText = "An empty starting point for this catalog.",
            )
          ),
      )
  }

@JsFun("""() => new URL(globalThis.location.href).searchParams.get('catalog') || 'm3-catalog'""")
private external fun uiBuilderCatalogFromPath(): String

@JsFun(
  """() => {
    const parts = globalThis.location.pathname.split('/').filter(Boolean);
    if (parts[0] !== 'ui-builder' || parts.length !== 2 || parts[1] === 'designs') return '';
    return decodeURIComponent(parts[1]);
  }"""
)
private external fun uiBuilderDesignFromPath(): String

/**
 * The canonical URL for one design: `/ui-builder/<designId>`.
 *
 * Only the identity and transport values survive as a query — they configure *who* is editing, not
 * *what*. `session`, `create`, `designId`, `template` and `state` do not: the first three are
 * implied by the path, and the last two only ever described how a design that now exists was
 * seeded.
 *
 * The selectors survive too, and they are the reason this takes arguments rather than reading the
 * URL for itself. They say **what in the design** the link means, so a legacy `?designId=` URL that
 * also named a node has to arrive at the path form still naming it — a rewrite that dropped them
 * would silently turn a link to one layer into a link to the design. `revision` and `node` are set
 * from the values the page opened with; the fragment is carried across verbatim, because it never
 * left the browser to begin with.
 */
@JsFun(
  """(designId, carried, revision, node) => {
    const current = new URL(globalThis.location.href);
    const path = '/ui-builder/' + encodeURIComponent(designId);
    const next = new URL(path, current.origin);
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) next.searchParams.set(name, value);
    });
    if (revision) next.searchParams.set('revision', revision);
    if (node) next.searchParams.set('node', node);
    next.hash = current.hash;
    if (next.toString() !== current.toString()) {
      globalThis.history.replaceState(null, '', next.toString());
    }
  }"""
)
private external fun canonicalizeUiBuilderUrlWith(
  designId: String,
  carried: String,
  revision: String,
  node: String,
)

/**
 * The identity query this page keeps, named once in [DESIGN_URL_IDENTITY_KEYS] rather than twice
 * here — the rewrite that keeps it and the link builder that refuses it have to agree, and the way
 * two hand-kept lists disagree is a token in a link somebody pasted into a chat.
 */
internal fun canonicalizeUiBuilderUrl(
  designId: String,
  selectors: DesignUrlSelectors,
) =
  canonicalizeUiBuilderUrlWith(
    designId,
    DESIGN_URL_IDENTITY_KEYS.joinToString(","),
    selectors.revision?.toString().orEmpty(),
    selectors.nodeId.orEmpty(),
  )

@JsFun("() => globalThis.location.search") private external fun locationSearch(): String

@JsFun("() => globalThis.location.hash") private external fun locationHash(): String

/**
 * The next time the address bar's fragment changes, whatever changed it.
 *
 * A fragment-only navigation — a second `#thread=` link followed from inside the open design, or
 * Back over one — is a *same-document* navigation: the browser does not reload, the Wasm app is
 * never re-entered, and the config parsed at startup keeps naming the thread that was open. Without
 * this the address bar and the panel disagree, which is the one failure a permalink cannot have.
 *
 * One promise per change rather than a persistent callback, because that is what a Kotlin/Wasm
 * caller can await, and it is the shape the paste listener already uses: re-armed by the loop that
 * consumed the last one.
 */
@JsFun(
  """() => new Promise((resolve) => {
    globalThis.addEventListener(
      'hashchange',
      () => resolve(globalThis.location.hash),
      { once: true },
    );
  })"""
)
private external fun awaitHashChangePromise(): Promise<JsString>

internal suspend fun awaitHashChange(): String = suspendCancellableCoroutine { continuation ->
  awaitHashChangePromise()
    .then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
    .catch { error ->
      if (continuation.isActive) {
        continuation.resumeWithException(IllegalStateException(error.toString()))
      }
      null
    }
}

/**
 * Takes one selector back out of the address bar, without a round trip and without a history entry.
 *
 * The address bar has to stop naming a node the moment somebody selects a different one, or the URL
 * they copy next — or that their browser restores tomorrow — points at a layer they have not been
 * looking at. `replaceState` rather than `pushState` for the same reason the canonical rewrite uses
 * it: changing selection is not navigation, and Back should leave the design.
 */
@JsFun(
  """(name) => {
    const current = new URL(globalThis.location.href);
    if (!current.searchParams.has(name)) return;
    current.searchParams.delete(name);
    globalThis.history.replaceState(null, '', current.toString());
  }"""
)
internal external fun dropDesignUrlQuery(name: String)

/** The same, for `#thread=`. Setting an empty hash drops the `#` with it. */
@JsFun(
  """() => {
    const current = new URL(globalThis.location.href);
    if (!current.hash) return;
    current.hash = '';
    globalThis.history.replaceState(null, '', current.toString());
  }"""
)
internal external fun dropDesignUrlFragment()

/**
 * Leaves a pinned revision for the design as it stands.
 *
 * A real navigation rather than a `replaceState`: the pinned page holds a snapshot of one committed
 * revision and no socket, so the head has to be fetched and subscribed to from a clean start. The
 * identity query rides along untouched, which is what makes this the same session rather than a
 * second sign-in.
 */
@JsFun(
  """() => {
    const current = new URL(globalThis.location.href);
    current.searchParams.delete('revision');
    globalThis.location.assign(current.toString());
  }"""
)
internal external fun goToLatestRevision()

/**
 * The same canonical URL, now naming this browser as where the design is kept.
 *
 * `replaceState` rather than a navigation: the page already has the app and the design is already
 * written, so reloading to reach the local mode would cost a round trip to a server the operator
 * may be about to leave behind. What the URL is for is the *next* visit.
 */
@JsFun(
  """(designId) => {
    const current = new URL(globalThis.location.href);
    const path = '/ui-builder/' + encodeURIComponent(designId);
    const next = new URL(path, current.origin);
    ['token', 'actor', 'clientId', 'displayName', 'color', 'endpoint', 'updatesEndpoint']
      .forEach((name) => {
        const value = current.searchParams.get(name);
        if (value !== null) next.searchParams.set(name, value);
      });
    next.searchParams.set('storage', 'local');
    globalThis.history.replaceState(null, '', next.toString());
  }"""
)
internal external fun enterLocalDesignUrl(designId: String)

/** Follow a `navigatePage` action while retaining only the identity and transport query values. */
@JsFun(
  """(catalogSystemId, designId) => {
    const current = new URL(globalThis.location.href);
    const path = '/ui-builder/' + encodeURIComponent(catalogSystemId) + '/' +
      encodeURIComponent(designId);
    const next = new URL(path, current.origin);
    ['token', 'actor', 'clientId', 'displayName', 'color', 'endpoint', 'updatesEndpoint']
      .forEach((name) => {
        const value = current.searchParams.get(name);
        if (value !== null) next.searchParams.set(name, value);
      });
    globalThis.location.assign(next.toString());
  }"""
)
internal external fun navigateToUiBuilderPage(catalogSystemId: String, designId: String)

/** The origin this page was served from, which is the server a design taken offline came from. */
@JsFun("""() => globalThis.location.origin""") internal external fun pageOrigin(): String

/**
 * Submit the New design form: a real `POST`, whose `303` the browser follows to the permalink.
 *
 * A form rather than `fetch`, because only a form submission makes the redirect a navigation —
 * `fetch` would follow the `303` itself and hand back the editor's HTML, leaving the page on the
 * URL the creation was requested from. The identity query rides on the action URL, where the server
 * reads it to authenticate the write and to carry it into the permalink it redirects to.
 */
@JsFun(
  """(catalogSystemId, designId, templateId, state, carried) => {
    const current = new URL(globalThis.location.href);
    const action = new URL(
      '/ui-builder/designs',
      current.origin,
    );
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) action.searchParams.set(name, value);
    });
    const form = globalThis.document.createElement('form');
    form.method = 'post';
    form.action = action.toString();
    const field = (name, value) => {
      const input = globalThis.document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    };
    field('designId', designId);
    field('catalog', catalogSystemId);
    field('template', templateId);
    if (state && state !== '[]') field('state', state);
    globalThis.document.body.appendChild(form);
    form.submit();
  }"""
)
private external fun navigateToNewDesignWith(
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: String,
  carried: String,
)

/** See [canonicalizeUiBuilderUrl]: one list of identity keys, read by both rewrites. */
internal fun navigateToNewDesign(
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: String,
) =
  navigateToNewDesignWith(
    catalogSystemId = catalogSystemId,
    designId = designId,
    templateId = templateId,
    state = state,
    carried = DESIGN_URL_IDENTITY_KEYS.joinToString(","),
  )

@JsFun(
  """() => globalThis.open('https://github.com/yschimke/compose-ui-builder/blob/main/docs/UI_BUILDER_GETTING_STARTED.md', '_blank', 'noopener,noreferrer')"""
)
internal external fun openUiBuilderGuide()

/**
 * A credential-free handoff: the agent asks for its own scoped grant instead of inheriting the
 * browser session. Keeping this beside the browser host makes the copied endpoint documentary, not
 * a second configuration format that can drift from the served page.
 */
internal fun openCodeUiBuilderPrompt(
  mcpEndpoint: String,
  designUrl: String,
  designId: String,
): String =
  """
  Work with the Compose UI Builder design `$designId`.

  First load the `compose-ui-builder` skill from https://github.com/yschimke/skills/tree/main/skills/compose-ui-builder and follow its collaboration and MCP guidance.

  Connect to this server's MCP endpoint:
  $mcpEndpoint

  The design is:
  $designUrl

  Request `ui-builder-read`, `ui-builder-write`, and `ui-builder-export` through the server's agent-access flow. Do not put a bearer token in a URL, command line, repository, or chat. Once approved, read the design and its comments before editing; use its current revision as `baseRevision`, make edits with `ui_builder_apply`, then check the Compose export with `ui_builder_export`. Report the design URL and revision after each visible step.
  """
    .trimIndent()

/**
 * Leave for the host's index of every design this account may open.
 *
 * The identity query rides along for the same reason it does on every other navigation here: on a
 * token-gated host the page that lands without it is a page that cannot list anything.
 */
@JsFun(
  """(carried) => {
    const current = new URL(globalThis.location.href);
    const next = new URL('/ui-builder/designs', current.origin);
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) next.searchParams.set(name, value);
    });
    globalThis.location.assign(next.toString());
  }"""
)
private external fun navigateToDesignsIndexWith(carried: String)

internal fun navigateToDesignsIndex() =
  navigateToDesignsIndexWith(DESIGN_URL_IDENTITY_KEYS.joinToString(","))

/**
 * An epoch millisecond as the reader's own locale writes it.
 *
 * The browser's job, not this module's: the home screen is drawn by common code that has no locale,
 * no time zone and no calendar, and an ISO instant is not what "when did I last touch this" looks
 * like to a person.
 */
@JsFun("""(millis) => new Date(millis).toLocaleString()""")
internal external fun formatLocalDateTime(millis: Double): String

/** Open one design by id, which is the home screen's row press. */
@JsFun(
  """(designId, carried) => {
    const current = new URL(globalThis.location.href);
    const next = new URL('/ui-builder/' + encodeURIComponent(designId), current.origin);
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) next.searchParams.set(name, value);
    });
    globalThis.location.assign(next.toString());
  }"""
)
private external fun navigateToDesignWith(designId: String, carried: String)

internal fun navigateToDesign(designId: String) =
  navigateToDesignWith(designId, DESIGN_URL_IDENTITY_KEYS.joinToString(","))

/**
 * Start a new design as a copy of an existing one: the same POST/Redirect/GET the New design form
 * uses, against the copy route, so the copy's permalink is what ends up in the address bar.
 */
@JsFun(
  """(sourceDesignId, designId, carried) => {
    const current = new URL(globalThis.location.href);
    const action = new URL('/ui-builder/designs/copy', current.origin);
    carried.split(',').forEach((name) => {
      const value = current.searchParams.get(name);
      if (value !== null) action.searchParams.set(name, value);
    });
    const form = globalThis.document.createElement('form');
    form.method = 'post';
    form.action = action.toString();
    const field = (name, value) => {
      const input = globalThis.document.createElement('input');
      input.type = 'hidden';
      input.name = name;
      input.value = value;
      form.appendChild(input);
    };
    field('sourceDesignId', sourceDesignId);
    field('designId', designId);
    globalThis.document.body.appendChild(form);
    form.submit();
  }"""
)
private external fun navigateToCopyDesignWith(
  sourceDesignId: String,
  designId: String,
  carried: String,
)

internal fun navigateToCopyDesign(sourceDesignId: String, designId: String) =
  navigateToCopyDesignWith(
    sourceDesignId,
    designId,
    DESIGN_URL_IDENTITY_KEYS.joinToString(","),
  )

@JsFun(
  """(name, fallback) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === null ? fallback : value;
  }"""
)
private external fun liveConfigValue(name: String, fallback: String): String

@JsFun("(name) => new URLSearchParams(globalThis.location.search).has(name)")
private external fun liveConfigPresent(name: String): Boolean

@JsFun(
  """(name) => {
    const value = new URLSearchParams(globalThis.location.search).get(name);
    return value === '1' || value === 'true';
  }"""
)
private external fun liveConfigFlag(name: String): Boolean

/**
 * A per-page nonce for operation ids, generated **without requiring a secure context**.
 *
 * `crypto.randomUUID` exists only in secure contexts. On a plain-HTTP origin — a LAN host, which is
 * exactly how somebody checks a preview from another laptop or a phone — it is `undefined`, and
 * calling it threw a `JsException` straight out of `liveSessionConfig` and killed the editor: a
 * blank page on `http://<host>.local:8723/` that worked on `http://127.0.0.1:8723/` (a trusted
 * origin) and on any HTTPS deployment. `crypto.getRandomValues` is available in insecure contexts
 * and is the fallback; `Math.random` is the last resort, because an operation id needs to be unique
 * within one page, not unguessable.
 */
@JsFun(
  """() => {
  const crypto = globalThis.crypto;
  if (crypto && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (crypto && typeof crypto.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }
  return 'nonce-' + Date.now().toString(16) + '-' + Math.random().toString(16).slice(2);
}"""
)
private external fun livePageNonce(): String

internal fun browserNowMillis(): Long = browserNow().toLong()

@JsFun("() => Date.now()") private external fun browserNow(): Double

/**
 * The page has settled: the harness's signal (`data-ui-builder-ready`), and the moment the boot
 * screen gives way to the editor.
 */
internal fun markReady() {
  markReadyAttribute()
  dismissBootScreen()
}

@JsFun("() => document.documentElement.setAttribute('data-ui-builder-ready', 'true')")
private external fun markReadyAttribute()

/**
 * Takes away the boot screen `index.html` draws before any script runs.
 *
 * Removed here rather than by `ui-builder-boot.js`, so a host whose CSP refuses that script still
 * gets its editor back. Idempotent, and a no-op for a shell that has no boot screen.
 *
 * At once, not faded out. [markReady] calls this in the same task that sets the ready attribute, so
 * nothing that waits for ready can ever see the screen: compose-preview-server's visual harness
 * screenshots two frames after it, and a 200 ms fade put a half-transparent boot screen in both of
 * its captures — at different opacities — until the comparison failed on every run.
 */
@JsFun(
  """() => {
  globalThis.composeUiBuilderBoot?.done();
  document.getElementById('ui-builder-boot')?.remove();
}"""
)
internal external fun dismissBootScreen()

/** What the boot screen says the editor is doing, until [dismissBootScreen]. */
@JsFun("(text) => globalThis.composeUiBuilderBoot?.phase(text)")
internal external fun bootPhase(text: String)

@JsFun(
  """(kind, revision, sequence) => {
    const state = globalThis.__uiBuilderPerformance || {
      schema: 'compose-ui-builder-performance/v1',
      protocolReceipts: [], authoritativeReceipts: [], canvasApplies: [], cleanRenders: [], phases: []
    };
    state.protocolReceipts.push({ kind, revision, sequence, receivedAtMs: performance.now() });
    if (state.protocolReceipts.length > 512) state.protocolReceipts.shift();
    globalThis.__uiBuilderPerformance = state;
  }"""
)
internal external fun recordProtocolReceipt(kind: String, revision: Int, sequence: Long)

@JsFun(
  """(revision, sequence) => {
    const state = globalThis.__uiBuilderPerformance || {
      schema: 'compose-ui-builder-performance/v1',
      protocolReceipts: [], authoritativeReceipts: [], canvasApplies: [], cleanRenders: [], phases: []
    };
    state.authoritativeReceipts.push({
      revision, sequence, receivedAtMs: performance.now(), consumed: false
    });
    if (state.authoritativeReceipts.length > 512) state.authoritativeReceipts.shift();
    globalThis.__uiBuilderPerformance = state;
  }"""
)
internal external fun recordAuthoritativeReceipt(revision: Int, sequence: Long)

@JsFun("() => performance.now()") internal external fun monotonicNow(): Double

@JsFun(
  """(name, revision, startedAtMs, completedAtMs) => {
    const state = globalThis.__uiBuilderPerformance;
    if (!state) return;
    state.phases = state.phases || [];
    state.phases.push({
      name, revision, startedAtMs, completedAtMs, durationMs: completedAtMs - startedAtMs
    });
    if (state.phases.length > 512) state.phases.shift();
  }"""
)
internal external fun recordPerformancePhase(
  name: String,
  revision: Int,
  startedAtMs: Double,
  completedAtMs: Double,
)

@JsFun(
  """(structurallyValid, wasmRenderable, pendingIds) => {
    globalThis.__uiBuilderCapabilityValidation = {
      structurallyValid,
      wasmRenderable,
      plannedOrUnsupportedComponentIds: pendingIds ? pendingIds.split(',') : []
    };
  }"""
)
internal external fun publishCapabilityDiagnostics(
  structurallyValid: Boolean,
  wasmRenderable: Boolean,
  pendingIds: String,
)

@JsFun(
  """(actorIdsJson, selectionsJson) => {
    globalThis.__uiBuilderPresence = {
      actorIds: JSON.parse(actorIdsJson),
      selections: JSON.parse(selectionsJson)
    };
    document.documentElement.dataset.uiBuilderCollaborators = String(globalThis.__uiBuilderPresence.actorIds.length);
  }"""
)
internal external fun publishPresenceManifest(actorIds: String, selections: String)

@JsFun(
  """(state) => {
    globalThis.__uiBuilderSocketState = state;
    document.documentElement.dataset.uiBuilderSocketState = state;
  }"""
)
internal external fun publishSocketState(state: String)

@JsFun(
  """(json) => {
    const manifest = JSON.parse(json);
    const token = (globalThis.__uiBuilderInspectionToken || 0) + 1;
    globalThis.__uiBuilderInspectionToken = token;
    manifest.generation.completed = false;
    globalThis.__uiBuilderInspection = manifest;
    let canvasApplied = false;
    const settle = (frames) => requestAnimationFrame(() => {
      const performanceState = globalThis.__uiBuilderPerformance;
      if (performanceState && !canvasApplied) {
        canvasApplied = true;
        const receipt = [...performanceState.authoritativeReceipts]
          .reverse()
          .find((candidate) =>
            candidate.revision === manifest.documentRevision && !candidate.consumed
          );
        if (receipt) {
          receipt.consumed = true;
          const completedAtMs = performance.now();
          performanceState.canvasApplies.push({
            revision: manifest.documentRevision,
            receiptAtMs: receipt.receivedAtMs,
            completedAtMs,
            latencyMs: completedAtMs - receipt.receivedAtMs
          });
          if (performanceState.canvasApplies.length > 512) performanceState.canvasApplies.shift();
        }
      }
      if (globalThis.__uiBuilderInspectionToken !== token) return;
      if (frames > 1) {
        settle(frames - 1);
        return;
      }
      manifest.generation.completed = true;
      globalThis.__uiBuilderInspection = manifest;
      document.documentElement.dataset.uiBuilderInspectionGeneration = manifest.generation.key;
      if (performanceState) {
        const completedAtMs = performance.now();
        const cleanRender = {
          revision: manifest.documentRevision,
          completedAtMs,
          generationKey: manifest.generation.key
        };
        performanceState.cleanRenders.push(cleanRender);
        if (performanceState.cleanRenders.length > 512) performanceState.cleanRenders.shift();
        if (!performanceState.interactive) performanceState.interactive = cleanRender;
      }
    });
    settle(manifest.generation.stabilityFrames);
  }"""
)
internal external fun publishInspection(json: String)

internal val inspectionJson = Json { encodeDefaults = true }

/**
 * Compose reports node bounds synchronously for every laid-out node. Publishing a complete encoded
 * manifest for every callback blocks the browser render opportunity with repeated whole-document
 * serialization. Yielding once coalesces that burst while retaining the latest complete snapshot;
 * [publishInspection] still places the canvas marker only after the subsequent animation frame.
 */
internal class CoalescingInspectionPublisher(private val scope: CoroutineScope) {
  private var pending: UiBuilderInspectionCollector? = null
  private var job: Job? = null

  fun offer(collector: UiBuilderInspectionCollector, revision: Int) {
    pending = collector
    if (job?.isActive == true) return
    val invalidatedAt = monotonicNow()
    recordPerformancePhase(
      name = "inspectionInvalidated",
      revision = revision,
      startedAtMs = invalidatedAt,
      completedAtMs = invalidatedAt,
    )
    job = scope.launch {
      yield()
      val latest = pending ?: return@launch
      pending = null
      val startedAt = monotonicNow()
      val snapshot = latest.snapshot()
      val encoded = inspectionJson.encodeToString(snapshot)
      recordPerformancePhase(
        name = "inspectionEncode",
        revision = snapshot.documentRevision,
        startedAtMs = startedAt,
        completedAtMs = monotonicNow(),
      )
      publishInspection(encoded)
    }
  }

  fun publish(snapshot: UiBuilderInspectionSnapshot) {
    publishInspection(inspectionJson.encodeToString(snapshot))
  }
}

internal fun publishEditorState(state: UiBuilderEditorState) {
  val selectedText =
    state.selectedNodeId
      ?.let(state.document.nodes::get)
      ?.properties
      ?.get("text")
      ?.jsonObject
      ?.get("value")
      ?.jsonPrimitive
      ?.contentOrNull
      .orEmpty()
  val selectedIconKey =
    state.selectedNodeId
      ?.let(state.document.nodes::get)
      ?.properties
      ?.get("iconKey")
      ?.jsonObject
      ?.get("value")
      ?.jsonPrimitive
      ?.contentOrNull
      .orEmpty()
  val mainBackgroundChildren =
    state.document.nodes["main-background"]?.slots?.get("children").orEmpty().joinToString(",")
  val outcome =
    when (state.lastOutcome) {
      null -> "idle"
      is CommandOutcome.Accepted -> "accepted"
      is CommandOutcome.Rejected -> "rejected:${state.lastOutcome.code}"
    }
  val rejection = state.lastOutcome as? CommandOutcome.Rejected
  val environment = state.document.screenEnvironmentSettings()
  publishEditorManifest(
    revision = state.document.revision,
    nodeCount = state.document.nodes.size,
    selectedNodeId = state.selectedNodeId.orEmpty(),
    catalogQuery = state.catalogQuery,
    operationSequence = state.operationSequence,
    outcome = outcome,
    selectedText = selectedText,
    selectedIconKey = selectedIconKey,
    mainBackgroundChildren = mainBackgroundChildren,
    documentHash = sha256Hex(canonicalDocument(state.document)),
    outcomeNodeId = rejection?.nodeId.orEmpty(),
    outcomeField = rejection?.field.orEmpty(),
    outcomeMessage = rejection?.message.orEmpty(),
    widthDp = environment.widthDp,
    heightDp = environment.heightDp,
    density = environment.density,
    fontScale = environment.fontScale,
    locale = environment.locale,
    theme = environment.theme.wireValue,
    layoutDirection = environment.layoutDirection.wireValue,
  )
}

@JsFun(
  """(revision, nodeCount, selectedNodeId, catalogQuery, operationSequence, outcome, selectedText, selectedIconKey, mainBackgroundChildren, documentHash, outcomeNodeId, outcomeField, outcomeMessage, widthDp, heightDp, density, fontScale, locale, theme, layoutDirection) => {
    globalThis.__uiBuilderEditor = {
      revision,
      nodeCount,
      selectedNodeId,
      catalogQuery,
      operationSequence,
      outcome,
      selectedText,
      selectedIconKey,
      mainBackgroundChildren: mainBackgroundChildren ? mainBackgroundChildren.split(',') : [],
      documentHash,
      outcomeNodeId,
      outcomeField,
      outcomeMessage,
      environment: { widthDp, heightDp, density, fontScale, locale, theme, layoutDirection }
    };
    document.documentElement.dataset.uiBuilderEditorRevision = String(revision);
  }"""
)
private external fun publishEditorManifest(
  revision: Int,
  nodeCount: Int,
  selectedNodeId: String,
  catalogQuery: String,
  operationSequence: Int,
  outcome: String,
  selectedText: String,
  selectedIconKey: String,
  mainBackgroundChildren: String,
  documentHash: String,
  outcomeNodeId: String,
  outcomeField: String,
  outcomeMessage: String,
  widthDp: Int,
  heightDp: Int,
  density: Double,
  fontScale: Double,
  locale: String,
  theme: String,
  layoutDirection: String,
)

@JsFun(
  """(sourceWidthDp, sourceHeightDp, scale) => {
    globalThis.__uiBuilderEditorCanvas = {
      ...(globalThis.__uiBuilderEditorCanvas || {}), sourceWidthDp, sourceHeightDp, scale
    };
  }"""
)
internal external fun publishEditorCanvasMetrics(
  sourceWidthDp: Int,
  sourceHeightDp: Int,
  scale: Float,
)

internal fun publishEditorCanvasBounds(bounds: androidx.compose.ui.geometry.Rect) {
  publishEditorCanvasBoundsValues(bounds.left, bounds.top, bounds.right, bounds.bottom)
}

@JsFun(
  """(left, top, right, bottom) => {
    const current = globalThis.__uiBuilderEditorCanvas || {};
    globalThis.__uiBuilderEditorCanvas = {
      ...current,
      bounds: { left, top, right, bottom, width: right - left, height: bottom - top }
    };
  }"""
)
private external fun publishEditorCanvasBoundsValues(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
)

@JsFun(
  """(hovered, label) => {
    globalThis.__uiBuilderEditorDropTarget = { hovered, label };
  }"""
)
internal external fun publishEditorDropTarget(hovered: Boolean, label: String)

/**
 * The component packs remembered as on for [catalogSystemId], from this browser's storage.
 *
 * A browser setting rather than a server one, and per catalog rather than per design: which shelves
 * a palette shows is a preference of the person at the keyboard, not a fact about the document, and
 * it is the same answer for every design of one catalog. Storage can be absent or refuse — a
 * private window, a blocked origin — and either reads as "nothing remembered".
 */
internal fun readEnabledPacks(catalogSystemId: String): Set<String> =
  readBrowserSetting(enabledPacksKey(catalogSystemId))
    .split(',')
    .map(String::trim)
    .filterTo(mutableSetOf(), String::isNotEmpty)

internal fun writeEnabledPacks(catalogSystemId: String, packs: Set<String>) {
  writeBrowserSetting(enabledPacksKey(catalogSystemId), packs.sorted().joinToString(","))
}

private fun enabledPacksKey(catalogSystemId: String): String = "ui-builder.packs.$catalogSystemId"

/**
 * The reader's pins for a catalog, or null while they have never said.
 *
 * The marker is what separates "never said" from "said none": an empty stored value is a reader who
 * unpinned everything, and reading that back as null would hand them the catalog's defaults again
 * on the next reload — the one behaviour that makes unstarring a default look broken.
 */
internal fun readPinnedComponents(catalogSystemId: String): Set<String>? {
  val stored = readBrowserSetting(pinnedComponentsKey(catalogSystemId))
  if (!stored.startsWith(PINNED_MARKER)) return null
  return stored
    .removePrefix(PINNED_MARKER)
    .split(',')
    .map(String::trim)
    .filterTo(mutableSetOf(), String::isNotEmpty)
}

internal fun writePinnedComponents(catalogSystemId: String, pins: Set<String>?) {
  writeBrowserSetting(
    pinnedComponentsKey(catalogSystemId),
    PINNED_MARKER + pins.orEmpty().sorted().joinToString(","),
  )
}

private const val PINNED_MARKER = "v1:"

private fun pinnedComponentsKey(catalogSystemId: String): String =
  "ui-builder.pins.$catalogSystemId"

@JsFun(
  """(key) => {
    try {
      return globalThis.localStorage?.getItem(key) ?? '';
    } catch (e) {
      return '';
    }
  }"""
)
private external fun readBrowserSetting(key: String): String

@JsFun(
  """(key, value) => {
    try {
      if (value === '') globalThis.localStorage?.removeItem(key);
      else globalThis.localStorage?.setItem(key, value);
    } catch (e) {}
  }"""
)
private external fun writeBrowserSetting(key: String, value: String)

private const val HOME_FOLDERS_KEY = "ui-builder.home-folders.v1"

/** Tolerant read: a broken preference must never hide a server design. */
internal fun readHomeFolders(): Map<String, String> = runCatching {
  Json.decodeFromString<Map<String, String>>(readBrowserSetting(HOME_FOLDERS_KEY))
}
  .getOrDefault(emptyMap())
  .filter { (designId, folder) -> designId.matches(NEW_DESIGN_ID) && folder.isNotBlank() }

internal fun writeHomeFolders(folders: Map<String, String>) {
  writeBrowserSetting(HOME_FOLDERS_KEY, Json.encodeToString(folders))
}

/**
 * What the status line calls this session: live against the server, or this browser's own copy.
 *
 * The offline spelling is not cosmetic. A local session that is *also* offline has fallen back to a
 * catalog remembered from an earlier visit, and an author should be told that the palette in front
 * of them is a memory rather than what the server serves today.
 */
internal fun sessionModeLabel(config: LiveSessionConfig, session: BrowserLocalSession?): String =
  when {
    !config.localStorage -> "Live"
    session?.offline == true -> "This browser · offline"
    else -> "This browser"
  }

/**
 * Seeds one design into this browser, or says why it could not be.
 *
 * The seed comes from [UiBuilderNewDesignSeed], the same object the server's New design form runs,
 * so "a blank Wear screen" means one thing whichever route made it. The operations fixture it reads
 * the environment from is a static file, so it goes through the browser's remembered-text cache and
 * a second design can be made with the network gone.
 */
internal suspend fun createLocalDesign(
  session: BrowserLocalSession,
  catalogs: List<CatalogCapabilityV1>,
  catalogSystemId: String,
  designId: String,
  templateId: String,
  state: List<NewDesignState>,
): String? {
  val catalog =
    catalogs.firstOrNull { it.benchmark.catalogSystemId == catalogSystemId }
      ?: return "this browser has no stored $catalogSystemId catalog to pin a new design to"
  val document =
    try {
      UiBuilderNewDesignSeed.document(
        designId = designId,
        catalogSystemId = catalogSystemId,
        templateId = templateId,
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(session.text.text(NEW_DESIGN_FIXTURE_PATH) { fetchText(it) })
            .jsonObject,
        state = state,
      )
    } catch (failure: Exception) {
      return failure.message ?: "the design could not be seeded"
    }
  val response = session.service.create(document)
  return (response as? ErrorResponseV1)?.error?.message
}

/**
 * Copies the design the server is serving into this browser, with the fork point it forked at.
 *
 * The digest and the document come from the same answer on purpose: it is the server's own
 * `documentHash` for that revision, so the claim "this copy forked from revision N of that design"
 * is checkable when it comes home rather than merely asserted.
 *
 * Refuses a design id this browser already holds, for the reason create refuses to replace: two
 * histories under one name is the one thing a later sync could not sort out.
 */
internal fun takeDesignOffline(
  wire: DesignDocumentV1,
  catalogSystemId: String,
  sequence: Long,
): String? {
  val store = LocalDesignStore(BrowserLocalDesignStorage())
  if (store.read(wire.id) != null) {
    return "this browser already holds a design called ${wire.id}"
  }
  val document = wire.toRendererDocument()
  return try {
    store.write(
      localCheckoutRecord(
        document = document,
        documentDigest = wire.canonicalDocumentHash(),
        catalogSystemId = catalogSystemId,
        sequence = sequence,
        server = pageOrigin(),
        nowEpochMillis = browserNowMillis(),
      )
    )
    null
  } catch (failure: LocalDesignStorageException) {
    failure.message ?: "this browser refused to store the design"
  }
}

/** The status line a sync leaves behind: never silent, and never only "done". */
internal fun syncStatus(result: LocalSyncResult): String =
  when (result) {
    is LocalSyncResult.NotLinked ->
      "This design was made in this browser, so there is nothing to sync it into — publish it as a new design instead"
    is LocalSyncResult.ForkPointGone ->
      "Sync refused · the server no longer keeps revision ${result.revision}, which this copy forked from — publish it as a new design instead"
    is LocalSyncResult.ForkPointDisagrees ->
      "Sync refused · revision ${result.revision} on the server is not the document this copy forked from"
    is LocalSyncResult.Unreachable -> "Sync failed · ${result.message} — nothing was sent"
    is LocalSyncResult.Refused -> "Sync refused · ${result.code}: ${result.message}"
    is LocalSyncResult.Replayed ->
      (if (result.report.complete) "Synced · " else "Synced in part · ") + result.report.summary()
  }

/** The operations fixture every new design reads its environment from, beside the Wasm bundle. */
private const val NEW_DESIGN_FIXTURE_PATH = "jetcaster-discover-operations-v1.json"

internal const val LOCAL_COMMENTS_UNAVAILABLE =
  "Comments need the server. This design is kept in this browser, so there is nobody to discuss it with yet."

internal const val LOCAL_REFERENCE_UNAVAILABLE =
  "Reference pictures need the server. This design is kept in this browser, which has room for the document but not for screenshots."

internal const val LOCAL_NATIVE_RENDER_UNAVAILABLE =
  "A native render is drawn by the server from the stored design, and this design is kept in this browser."
