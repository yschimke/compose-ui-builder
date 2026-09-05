package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcRemark
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adding one of the serving catalog's published Remote Compose documents.
 *
 * The insert is what makes `remote-m3` authorable at all: before it, `remote-compose/document`
 * could be added but only ever held an empty `documentBase64`, which the renderer draws as its own
 * error box.
 */
class EditorRemoteComposeInsertTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val source =
    RemoteComposeSource("appcard__ideal__default__compact", "App card", "appcard")
  private val encoded =
    Base64.Default.encode(
      RcDocumentCodec.encode(
        RcDocument(
          header = RcHeader(RcVersion(0, 1, 0)),
          operations = listOf(RcRemark("published sticker")),
        )
      )
    )

  @Test
  fun `an added source lands as a document the renderer can play`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = assertNotNull(reducer.dropTarget(initial, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID))

    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target),
      )

    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome)
    val nodeId = assertNotNull(inserted.selectedNodeId)
    val node = inserted.document.nodes.getValue(nodeId)
    assertEquals(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID, node.componentId)
    val stored =
      node.properties.getValue("documentBase64").jsonObject.getValue("value").jsonPrimitive.content
    assertEquals(encoded, stored)
    assertTrue(decodeRemoteComposeDocument(stored).isSuccess)
  }

  @Test
  fun `bytes that are not a document are refused rather than saved`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = assertNotNull(reducer.dropTarget(initial, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID))

    // What a catalog lane answering with an HTML error page looks like by the time it reaches here.
    val refused =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertRemoteComposeDocument(
          source,
          "PGh0bWw+bm90aGVyZTwvaHRtbD4=",
          target,
        ),
      )

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(RejectionCode.INVALID_PROPERTY, outcome.code)
    assertTrue(outcome.message.contains(source.id), outcome.message)
    assertEquals(document.nodes.size, refused.document.nodes.size)
  }

  @Test
  fun `a target the selection no longer resolves to is refused`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = assertNotNull(reducer.dropTarget(initial, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID))
    // The fetch is a round trip, so the selection can move under it. The target is re-derived from
    // the selection as it stands, and a stale one is refused rather than inserted somewhere the
    // author is no longer looking.
    val moved = reducer.initial(document, selectedNodeId = document.roots.first())
    assertTrue(reducer.dropTarget(moved, REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID) != target)

    val refused =
      reducer.reduce(
        moved,
        UiBuilderEditorEvent.InsertRemoteComposeDocument(source, encoded, target),
      )

    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(RejectionCode.INVALID_LOCATION, outcome.code)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
