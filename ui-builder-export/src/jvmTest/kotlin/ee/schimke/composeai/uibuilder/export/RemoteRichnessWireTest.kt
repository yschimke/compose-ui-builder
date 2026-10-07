package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExpressionValueV1
import ee.schimke.composeai.uibuilder.protocol.RemoteCallModifierV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull

/**
 * A design that uses the richer Remote vocabulary — formulas, a system clock value, a drawing, a
 * `remoteCall` modifier — survives the wire: it is what `.uid` saves and the service commits
 * (compose-preview-contracts 3.20.0, #141).
 */
class RemoteRichnessWireTest {
  private val document: UiBuilderDocument =
    Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          // The wire document needs a full catalog pin and environment; the gauge leaves them out.
          RemoteDrawingExportTest.GAUGE.replace(
            "\"catalogPin\":{},\"environment\":{}",
            """
            "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
              "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
            "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
              "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"}
            """
              .trimIndent(),
          )
        ) as JsonObject
      )
      .let { gauge ->
        val canvas = gauge.nodes.getValue("canvas")
        gauge.copy(
          nodes =
            gauge.nodes +
              ("canvas" to
                canvas.copy(
                  modifiers =
                    JsonArray(
                      canvas.modifiers +
                        Json.parseToJsonElement(
                          """{"type":"remoteCall","name":"border","args":{
                            "width":{"type":"float","value":2},
                            "color":{"type":"colorToken","value":"primary"}}}"""
                        )
                    )
                ))
        )
      }

  @Test
  fun `the contracts carry computed values, so the formula editor is offered`() {
    assertTrue(UiExpressions.wireSupported)
  }

  @Test
  fun `formulas, system values and Remote calls round-trip through the wire document`() {
    val wire = document.toDesignDocumentV1()

    val sweep = wire.nodes.getValue("sweep").properties.getValue("sweepAngle")
    assertIs<ExpressionValueV1>(sweep)
    val border = wire.nodes.getValue("canvas").modifiers.last()
    assertIs<RemoteCallModifierV1>(border)
    assertEquals("border", border.name)

    // The wire writes a float literal as a double (8 comes back 8.0), so numbers compare by value.
    val back = wire.toUiBuilderDocument()
    listOf("sweep", "hand", "canvas").forEach { id ->
      assertEquals(
        numbers(document.nodes.getValue(id).properties),
        numbers(back.nodes.getValue(id).properties),
        id,
      )
      assertEquals(
        numbers(document.nodes.getValue(id).modifiers),
        numbers(back.nodes.getValue(id).modifiers),
        id,
      )
    }
  }

  private fun numbers(element: JsonElement): JsonElement =
    when (element) {
      is JsonObject -> JsonObject(element.mapValues { numbers(it.value) })
      is JsonArray -> JsonArray(element.map(::numbers))
      is JsonPrimitive ->
        if (element.isString) element else element.doubleOrNull?.let(::JsonPrimitive) ?: element
      else -> element
    }
}
