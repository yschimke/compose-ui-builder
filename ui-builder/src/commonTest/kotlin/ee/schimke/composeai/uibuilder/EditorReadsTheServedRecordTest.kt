package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which record the code pane and the problems panel judge a design by.
 *
 * The editor had exactly one: `m3-catalog`'s authored record, embedded in this module at build time
 * by `:ui-builder:embedComponentRecord`, whatever catalog the design was pinned to. Against a
 * **published** catalog that is not an incomplete record, it is the wrong one — the shelf is
 * composed from a record fetched by the host, and the components it adds are not in this build at
 * all. So the pane answered "no component `m3/…` in this catalog" for components the server's
 * export wrote call sites for, which is the browser/server disagreement `ScreenExportGate` was
 * extracted to prevent (compose-preview-server#714).
 *
 * The host now serves that record and the editor reads it. These two tests are the wiring: the same
 * catalog and the same design, asked once with the served record and once without.
 *
 * The **parity** claim — that the two lanes then give the same answer for every component of a real
 * published shelf — is `PublishedGeneratedM3CatalogEquivalenceTest`'s, where both lanes and the
 * generated m3 fixtures are on one classpath. This is the half that fails if nothing hands the
 * record to the reducer, which is the defect it is pinning.
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
    // The same catalog and the same design, judged by the record embedded in this build. It has no
    // `m3/badge`, and the sentence below is the one a designer read while the export worked — kept
    // as the assertion rather than deleted, because "nothing is served" is a real state (a host
    // with no record for this catalog, an older host with no such route, a page that could not
    // reach it) and falling back to it must stay a refusal rather than become an invented call.
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
   * What the host serves for that catalog: its own record, already wearing the builder ids the
   * published file gave its components (`ScreenGeneratorComposeExportExecutor.exportRecord`).
   *
   * Decoded from JSON rather than constructed, so this test describes the bytes that cross the wire
   * and not a Kotlin constructor it happens to share with the producer.
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
