package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which record the code pane and the problems panel judge a design by. The embedded `m3-catalog`
 * record (from `:ui-builder:embedComponentRecord`) is the wrong one for a **published** catalog,
 * whose shelf comes from a host-fetched record; the host now serves that record and the editor
 * reads it (see compose-preview-server#714).
 *
 * These two tests are the wiring: same catalog and design, with and without the served record. Full
 * lane parity over a real published shelf is `PublishedGeneratedM3CatalogEquivalenceTest`'s.
 */
class EditorReadsTheServedRecordTest {

  @Test
  fun `a component only the served record carries is generated, not refused`() {
    val reducer = UiBuilderEditorReducer(catalog, catalogRecord = servedRecord)

    val generated = reducer.generatedCode(design)

    val source = assertIs<EditorGeneratedCode.Source>(generated, "$generated")
    assertTrue("Badge()" in source.kotlin, source.kotlin)
  }

  @Test
  fun `without it the pane still says what it always said`() {
    // Without a served record (no record for this catalog, an older host, or unreachable), the
    // embedded record lacks `m3/badge`, and the fallback must stay a refusal rather than invent a
    // call.
    val reducer = UiBuilderEditorReducer(catalog, catalogRecord = null)

    val generated = reducer.generatedCode(design)

    val refused = assertIs<EditorGeneratedCode.Refused>(generated, "$generated")
    assertEquals(
      listOf("no component `m3/badge` in this catalog"),
      refused.reasons.filter { "m3/badge" in it },
      refused.reasons.toString(),
    )
  }

  /** A one-component shelf offering an id the embedded record has never carried. */
  private val catalog =
    CapabilityCatalogParser.parse(
      """
      {
        "schema": "compose-ui-builder-catalog/v1-candidate",
        "benchmark": {
          "id": "m3-catalog",
          "sourceRevision": "test",
          "catalogSystemId": "m3-catalog",
          "catalogRevision": "test",
          "nativeRuntimeId": "test"
        },
        "components": [
          {
            "componentId": "m3/badge",
            "displayName": "Badge",
            "role": "Leaf",
            "wasm": { "platformSupported": false, "adapterStatus": "unsupported" },
            "code": { "symbol": "androidx.compose.material3.Badge" }
          }
        ]
      }
      """
    )

  /**
   * What the host serves for that catalog: its own record, already using the builder ids
   * (`ScreenGeneratorComposeExportExecutor.exportRecord`). Decoded from JSON so the test describes
   * the wire bytes.
   */
  private val servedRecord: ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      """
        {
          "schemaVersion": 2,
          "module": "catalog",
          "variant": "test",
          "components": [
            {
              "canonicalId": "catalog/androidx.compose.material3.BadgeKt.Badge",
              "componentIds": ["m3/badge"],
              "symbol": {
                "jvmOwner": "androidx.compose.material3.BadgeKt",
                "callable": "androidx.compose.material3.Badge",
                "name": "Badge",
                "origin": "LIBRARY"
              },
              "parameters": [
                {
                  "name": "modifier",
                  "type": "Modifier",
                  "hasDefault": true,
                  "typeFqn": "androidx.compose.ui.Modifier"
                }
              ],
              "slots": [],
              "code": { "call": "Badge()", "imports": ["androidx.compose.material3.Badge"] },
              "signatureKnown": true
            }
          ]
        }
        """
    )

  private val design =
    UiBuilderDocument(
      schema = "ui-builder/v1",
      id = "served-record",
      title = "Served record",
      revision = 1,
      catalogPin =
        JsonObject(
          mapOf(
            "systemId" to JsonPrimitive("m3-catalog"),
            "catalogRevision" to JsonPrimitive("test"),
            "capabilityDigest" to JsonPrimitive("test"),
            "nativeRuntimeId" to JsonPrimitive("test"),
          )
        ),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(900),
            "heightDp" to JsonPrimitive(1400),
            "density" to JsonPrimitive(2.0),
            "theme" to JsonPrimitive("light"),
            "locale" to JsonPrimitive("en-US"),
            "fontScale" to JsonPrimitive(1.0),
            "layoutDirection" to JsonPrimitive("ltr"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("subject"),
      nodes = mapOf("subject" to UiBuilderNode(id = "subject", componentId = "m3/badge")),
    )
}
