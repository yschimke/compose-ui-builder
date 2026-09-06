package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.rcplayer.protocol.RcCustomLayout
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A mobile screen with Remote Compose content in the middle of it, and Compose back inside that.
 *
 * The three-scope tree from `docs/design/UI_BUILDER_REMOTE_COMPOSE.md`, drawn through
 * [UiBuilderSurface] — the same renderer the editor canvas and the production export use — so this
 * is what an author sees rather than a mock of it. The two dashed frames are the point: the outer
 * one marks where the vocabulary switches to `@RemoteComposable`, the inner one names the custom
 * component a host has to register for the content inside it to draw on a real player.
 *
 * Wired here so the next change to either frame is diffed by the preview workflow rather than
 * noticed by somebody.
 */
@Preview(widthDp = 320, heightDp = 260)
@Composable
fun InlineRemoteContentPreview() {
  UiBuilderSurface(
    document = inlineRemoteContentDocument(),
    editorOverlay = false,
  )
}

/**
 * The same screen once its remote content has been **captured** and is played for real.
 *
 * The counterpart of [InlineRemoteContentPreview] and the reason both are wired: they are the two
 * states of one node, and the difference between them is the whole feature. Above, the subtree is
 * drawn with Compose stand-ins because nobody has captured it — the shapes the generated
 * `@RemoteComposable` body *describes*. Here a document exists, so `RcComposePlayer` draws it and
 * the design's own Compose fills the `field` custom component through
 * [RcCustomComponentRegistry][ee.schimke.composeai.rcplayer.compose.RcCustomComponentRegistry],
 * which is exactly what a host does with a registered renderer on a watch.
 *
 * The frame survives the upgrade and stops standing in for anything: the boundary is still a fact
 * about the design, and `played` beside the label is what says the pixels inside it are a player's.
 *
 * The fixture is built here rather than captured, because a preview has no Android daemon to run
 * `captureSingleRemoteDocument` on. What it stands for is the shape a capture returns — a root, and
 * the custom component the emitted body writes for a `remote-compose/custom` node — and the bytes
 * are real: they are encoded and parsed by the same codec and played by the same player as any
 * document this builder embeds.
 */
@Preview(widthDp = 320, heightDp = 260)
@Composable
fun PlayedInlineRemoteContentPreview() {
  CompositionLocalProvider(
    LocalRemoteComposeCaptures provides
      { nodeId ->
        if (nodeId == "remote") Result.success(capturedInlineDocument()) else null
      }
  ) {
    UiBuilderSurface(
      document = inlineRemoteContentDocument(),
      editorOverlay = false,
    )
  }
}

/**
 * What a capture of the design's `remote` node comes back as, in the smallest honest form.
 *
 * One `LAYOUT_CUSTOM` operation naming `field`, which is the operation `RemoteCustomComponent(name
 * = "field")` writes and the one the design's own `remote-compose/custom` node registers a renderer
 * for. A document with no custom component would play as an empty box and prove nothing about the
 * seam this preview exists to show.
 */
internal fun capturedInlineDocument(width: Int = 300, height: Int = 96): RcDocument =
  RcDocument(
    header = RcHeader(RcVersion(0, 1, 0), legacyWidth = width, legacyHeight = height),
    operations =
      listOf(
        RcTextData(1, "field"),
        RcRootLayout(10),
        RcCustomLayout(componentId = 20, animationId = 0, configId = 1, properties = emptyList()),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      ),
  )

/**
 * The same screen with its remote content sourced by URL instead of authored.
 *
 * Nothing resolves it here — a preview has no host to fetch with — which is exactly the state worth
 * having a picture of: an unresolved `documentUrl` draws as a *waiting* surface rather than an
 * error box, because a design pointing at a URL nobody has fetched is not a broken design.
 */
@Preview(widthDp = 320, heightDp = 200)
@Composable
fun UnresolvedRemoteComposeUrlPreview() {
  UiBuilderSurface(
    document = remoteComposeUrlDocument(),
    editorOverlay = false,
  )
}

internal fun inlineRemoteContentDocument(): UiBuilderDocument =
  previewDocument(
    title = "Player screen",
    roots = listOf("screen"),
    nodes =
      listOf(
        column("screen", listOf("intro", "remote", "outro")),
        text("intro", "Library"),
        UiBuilderNode(
          id = "remote",
          componentId = REMOTE_COMPOSE_INLINE_COMPONENT_ID,
          slots = mapOf("content" to listOf("remoteColumn")),
        ),
        column("remoteColumn", listOf("remoteLabel", "custom", "remoteHint")),
        text("remoteLabel", "Search"),
        UiBuilderNode(
          id = "custom",
          componentId = REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
          properties =
            buildJsonObject {
              putJsonObject("name") {
                put("type", "string")
                put("value", "field")
              }
            },
          slots = mapOf("content" to listOf("field")),
        ),
        text("field", "Type to search…"),
        text("remoteHint", "Recent"),
        text("outro", "Recently played"),
      ),
  )

private fun remoteComposeUrlDocument(): UiBuilderDocument =
  previewDocument(
    title = "Player screen",
    roots = listOf("screen"),
    nodes =
      listOf(
        column("screen", listOf("intro", "embedded")),
        text("intro", "Library"),
        UiBuilderNode(
          id = "embedded",
          componentId = REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID,
          properties =
            buildJsonObject {
              putJsonObject("documentUrl") {
                put("type", "string")
                put("value", "/remote-m3/render/appcard__ideal__default__compact.rc")
              }
            },
        ),
      ),
  )

private fun column(id: String, children: List<String>) =
  UiBuilderNode(id = id, componentId = "layout/column", slots = mapOf("children" to children))

private fun text(id: String, value: String) =
  UiBuilderNode(
    id = id,
    componentId = "m3/text",
    properties =
      buildJsonObject {
        putJsonObject("text") {
          put("type", "string")
          put("value", value)
        }
      },
  )

private fun previewDocument(title: String, roots: List<String>, nodes: List<UiBuilderNode>) =
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "inline-remote-content-preview",
    title = title,
    revision = 1,
    catalogPin = inlineRemoteContentCatalogPin,
    environment = inlineRemoteContentEnvironment,
    stateVariables = JsonObject(emptyMap()),
    roots = roots,
    nodes = nodes.associateBy { it.id },
  )

private val inlineRemoteContentCatalogPin: JsonObject = buildJsonObject {
  put("systemId", JsonPrimitive("m3-catalog"))
  put("catalogRevision", JsonPrimitive("candidate"))
  put("capabilityDigest", JsonPrimitive("candidate"))
  put("nativeRuntimeId", JsonPrimitive("candidate"))
}

/** Settled and fixed, for the reason the Wear widget previews give: a moving render cannot diff. */
private val inlineRemoteContentEnvironment: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "widthDp": 320,
        "heightDp": 260,
        "density": 1.0,
        "theme": "light",
        "dynamicColor": false,
        "locale": "en-US",
        "fontScale": 1.0,
        "layoutDirection": "ltr",
        "windowPosture": "flat",
        "browserZoomPercent": 100,
        "fixedTime": "2024-05-16T12:00:00Z",
        "animations": "settled",
        "networkAccess": false
      }
      """
    )
    .jsonObject
