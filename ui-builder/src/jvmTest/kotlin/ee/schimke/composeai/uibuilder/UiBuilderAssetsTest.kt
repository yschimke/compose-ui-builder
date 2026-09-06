package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `asset/image` resolves against the document's registry first, and never fails a frame.
 *
 * The second half is yschimke/compose-preview-server#484: one accepted node with a key the renderer
 * did not know used to throw out of composition and take the whole design down, in the editor and
 * behind every export. Every lane now goes through [resolveAsset], and a key nothing resolves is a
 * drawn placeholder.
 */
class UiBuilderAssetsTest {

  @Test
  fun `a key resolves to the document's registry before anything the build ships`() {
    val document =
      document(
        node("photo", "avatar"),
        assets =
          mapOf(
            "avatar" to embedded(PNG_1X1, "sha256:aa"),
            "remote" to uploaded("sha256:bb"),
            ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY to uploaded("sha256:cc"),
          ),
      )
    val avatar = assertIs<ResolvedUiBuilderAsset.Embedded>(document.resolveAsset("avatar"))
    assertEquals("sha256:aa", avatar.contentDigest)
    assertTrue(avatar.bytes.contentEquals(PNG_1X1))
    val remote = assertIs<ResolvedUiBuilderAsset.Uploaded>(document.resolveAsset("remote"))
    assertEquals("sha256:bb", remote.storageKey)
    // The registry wins over the built-in key of the same name.
    assertIs<ResolvedUiBuilderAsset.Uploaded>(
      document.resolveAsset(ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY)
    )
    assertIs<ResolvedUiBuilderAsset.ProjectOwned>(
      document(node("p", "x")).resolveAsset(ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY)
    )
    assertEquals(ResolvedUiBuilderAsset.Generated, document.resolveAsset(GATE0_COVER_ASSET_KEY))
    assertEquals(
      ResolvedUiBuilderAsset.Missing("editor.placeholder"),
      document.resolveAsset("editor.placeholder"),
    )
    assertTrue(avatar.hasRasterBytesOffline())
    assertFalse(remote.hasRasterBytesOffline())
    assertEquals(
      mapOf(
        "remote" to remote,
        ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY to
          document.resolveAsset(ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY),
      ),
      document.uploadedAssets(),
    )
  }

  @Test
  fun `a key nothing resolves draws a placeholder instead of failing the frame`() {
    val document = document(node("photo", "avatar-lain"))
    // Composes the whole surface through the same renderer the editor and the daemon use; before
    // the registry this threw `unsupported asset 'avatar-lain' on photo` out of the scene.
    val recording = JvmSkiaStructuredSvgRecorder.record(document)
    assertTrue(recording.svg.contains("<svg"), recording.svg)
    assertTrue(recording.rasterRecords.isEmpty())
    // Vector only: no raster to correlate, and no text the recorder would have to refuse — the
    // key label is drawn on the canvas and in a PNG, where nothing has to attribute it.
    assertFalse(Regex("<image\\b").containsMatchIn(recording.svg), recording.svg)
    assertFalse(recording.svg.contains("<text"), recording.svg)
  }

  @Test
  fun `an embedded asset is drawn from the document's own bytes`() {
    val document =
      document(
        node("photo", "avatar"),
        assets = mapOf("avatar" to embedded(PNG_1X1, PNG_1X1_DIGEST)),
      )
    val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
    val readiness = readiness(document, catalog)
    assertEquals(listOf("photo"), readiness.declaredRasterFallbackNodeIds)

    val recording =
      JvmSkiaStructuredSvgRecorder.record(StructuredSvgRecordingRequest(document, listOf("photo")))
    val raster = assertNotNull(recording.rasterRecords.singleOrNull())
    assertEquals("photo", raster.nodeId)
    assertTrue(
      raster.sourceIdentity.startsWith("design-asset/v1/avatar/$PNG_1X1_DIGEST/"),
      raster.sourceIdentity,
    )
    assertTrue(Regex("<image\\b").containsMatchIn(recording.svg))

    // A key with no bytes behind it is not declared as a raster fallback: the recorder would have
    // nothing to embed, and the renderer's placeholder is vector.
    val unresolved = readiness(document(node("photo", "nope")), catalog)
    assertTrue(unresolved.declaredRasterFallbackNodeIds.isEmpty())
  }

  @Test
  fun `bytes that do not decode leave the design drawing`() {
    val document =
      document(
        node("photo", "broken"),
        assets = mapOf("broken" to embedded("not a png".encodeToByteArray(), "sha256:x")),
      )
    assertTrue(JvmSkiaStructuredSvgRecorder.record(document).svg.contains("<svg"))
  }

  private fun node(id: String, assetKey: String) = buildJsonObject {
    put("id", id)
    put("componentId", "asset/image")
    putJsonObject("properties") {
      putJsonObject("assetKey") {
        put("type", "assetKey")
        put("value", assetKey)
      }
      putJsonObject("contentScale") {
        put("type", "enum")
        put("value", "crop")
      }
    }
    put(
      "modifiers",
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "size")
            put("widthDp", 40)
            put("heightDp", 40)
          }
        )
      },
    )
    put("slots", buildJsonObject {})
    put("eventBindings", buildJsonObject {})
  }

  private fun document(
    node: JsonObject,
    assets: Map<String, JsonObject> = emptyMap(),
  ): UiBuilderDocument {
    // The picture sits inside a box: a root can never be a raster fallback (it would flatten the
    // whole design), and a design's picture is never the whole design anyway.
    val id = node.getValue("id").let { (it as JsonPrimitive).content }
    val box = buildJsonObject {
      put("id", "frame")
      put("componentId", "layout/box")
      put("properties", buildJsonObject {})
      put("modifiers", buildJsonArray {})
      put("slots", buildJsonObject { put("children", buildJsonArray { add(JsonPrimitive(id)) }) })
      put("eventBindings", buildJsonObject {})
    }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "asset-test",
      title = "Asset test",
      revision = 1,
      catalogPin =
        buildJsonObject {
          put("systemId", "m3-catalog")
          put("catalogRevision", "candidate")
          put("capabilityDigest", "candidate")
          put("nativeRuntimeId", "candidate")
        },
      environment =
        buildJsonObject {
          put("widthDp", 120)
          put("heightDp", 120)
          put("density", 1.0)
          put("theme", "light")
          put("dynamicColor", false)
          put("locale", "en-US")
          put("fontScale", 1.0)
          put("layoutDirection", "ltr")
          put("windowPosture", "flat")
          put("browserZoomPercent", 100)
          put("fixedTime", "2024-05-16T12:00:00Z")
          put("animations", "settled")
          put("networkAccess", false)
        },
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("frame"),
      nodes =
        mapOf(
          "frame" to Json.decodeFromJsonElement(UiBuilderNode.serializer(), box),
          id to Json.decodeFromJsonElement(UiBuilderNode.serializer(), node),
        ),
      assets = JsonObject(assets),
    )
  }

  private fun embedded(bytes: ByteArray, digest: String) = buildJsonObject {
    put("mediaType", "image/png")
    put("contentDigest", digest)
    putJsonObject("source") {
      put("type", "embedded")
      put("base64", Base64.Default.encode(bytes))
    }
  }

  private fun uploaded(digest: String) = buildJsonObject {
    put("mediaType", "image/png")
    put("contentDigest", digest)
    putJsonObject("source") {
      put("type", "uploaded")
      put("storageKey", digest)
    }
  }

  private fun readiness(
    document: UiBuilderDocument,
    catalog: ee.schimke.composeai.uibuilder.capability.CapabilityCatalog,
  ) =
    inspectDocumentSvgExport(document, catalog, DocumentSvgExecutionBridge.JVM_SKIA_SCENE_RECORDING)

  private fun resource(path: String): String =
    checkNotNull(UiBuilderAssetsTest::class.java.getResource(path)) { path }.readText()

  private companion object {
    val PNG_1X1: ByteArray =
      ByteArrayOutputStream()
        .also { out ->
          val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
          image.setRGB(0, 0, Color.RED.rgb)
          image.setRGB(1, 1, Color.BLUE.rgb)
          ImageIO.write(image, "png", out)
        }
        .toByteArray()
    const val PNG_1X1_DIGEST = "sha256:test"
  }
}
