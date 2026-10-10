package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.discovery.AdapterValueCodecs
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.discovery.TypedAdapterCatalog
import ee.schimke.composeai.discovery.TypedComponentAdapter
import ee.schimke.composeai.discovery.UI_BUILDER_POLICY_SCHEMA
import ee.schimke.composeai.discovery.UiBuilderCatalogs
import ee.schimke.composeai.discovery.UiBuilderPolicyFile
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.canvasAdapterRegistry
import ee.schimke.composeai.uibuilder.renderer.sdk.register
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The compiled component call and generated metadata share the definition used for dispatch. */
@OptIn(ExperimentalTestApi::class)
class TypedCatalogCanvasTest {
  private data class Props(val label: String, val onClick: () -> Unit)

  private class Adapter(record: ComponentRecord) :
    TypedComponentAdapter<Props>("acme/button", record) {
    val label = property(Props::label, AdapterValueCodecs.String, "Default label")
    val click = event(Props::onClick)
    val content = slot("content")
  }

  @Composable
  private fun BrandButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
  ) {
    Button(onClick = onClick, modifier = modifier) {
      Column {
        Text(label)
        content()
      }
    }
  }

  private data class CountProps(val count: Int)

  @Test
  fun `bad typed values show a local diagnostic and recover without stopping siblings`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      val record =
        ComponentRecord.Builder(
            ":app/acme.CountKt.Count",
            ComponentSymbol.Builder("acme.CountKt", "acme.Count", "Count", ComponentOrigin.PROJECT)
              .build(),
          )
          .also {
            it.signatureKnown = true
            it.parameters =
              listOf(
                TargetParameter.Builder("count", "Int").also { it.typeFqn = "kotlin.Int" }.build()
              )
          }
          .build()
      val adapter =
        object : TypedComponentAdapter<CountProps>("acme/count", record) {
          val count = property(CountProps::count, AdapterValueCodecs.Int, 3)
        }
      var renders = 0
      val registry = canvasAdapterRegistry {
        register(adapter) {
          renders++
          Text("Count ${value(adapter.count)}", modifier)
        }
      }
      fun document(value: JsonElement, type: String = "integer"): UiBuilderDocument {
        val empty = JsonObject(emptyMap())
        return UiBuilderDocument(
          schema = "compose-ui-builder-document/v1-candidate",
          id = "bad-value",
          title = "Bad value",
          revision = 0,
          catalogPin = empty,
          environment = empty,
          stateVariables = empty,
          roots = listOf("bad", "healthy"),
          nodes =
            mapOf(
              "bad" to
                UiBuilderNode(
                  id = "bad",
                  componentId = adapter.id,
                  properties =
                    buildJsonObject {
                      put(
                        "count",
                        buildJsonObject {
                          put("type", type)
                          put("value", value)
                        },
                      )
                    },
                ),
              "healthy" to
                UiBuilderNode(
                  id = "healthy",
                  componentId = "m3/text",
                  properties =
                    buildJsonObject {
                      put(
                        "text",
                        buildJsonObject {
                          put("type", "string")
                          put("value", "Healthy sibling")
                        },
                      )
                    },
                ),
            ),
        )
      }
      var current by mutableStateOf(document(JsonPrimitive(2.5)))
      setContent {
        UiBuilderSurface(
          current,
          catalogComponentIds = setOf(adapter.id, "m3/text"),
          canvasAdapterIds = mapOf(adapter.id to adapter.id),
          canvasAdapterRegistry = registry,
        )
      }
      onNodeWithText("acme/count: invalid properties", substring = true).assertExists()
      onNodeWithText("Healthy sibling").assertExists()
      runOnIdle { assertEquals(0, renders) }
      runOnIdle { current = document(JsonPrimitive("3.0")) }
      onNodeWithText("acme/count: invalid properties", substring = true).assertExists()
      onNodeWithText("Healthy sibling").assertExists()
      runOnIdle { assertEquals(0, renders) }
      runOnIdle { current = document(JsonPrimitive(3)) }
      onNodeWithText("Count 3").assertExists()
      onNodeWithText("acme/count: invalid properties", substring = true).assertDoesNotExist()
      onNodeWithText("Healthy sibling").assertExists()
    }

  @Test
  fun `generated catalog routes a real component with typed properties slots and callbacks`() =
    runDesktopComposeUiTest(width = 400, height = 400) {
      val record =
        ComponentRecord.Builder(
            ":app/acme.BrandButtonKt.BrandButton",
            ComponentSymbol.Builder(
                "acme.BrandButtonKt",
                "acme.BrandButton",
                "BrandButton",
                ComponentOrigin.PROJECT,
              )
              .build(),
          )
          .also { b ->
            b.signatureKnown = true
            b.parameters =
              listOf(
                TargetParameter.Builder("label", "String")
                  .also { it.typeFqn = "kotlin.String" }
                  .build(),
                TargetParameter.Builder("onClick", "() -> Unit")
                  .also {
                    it.typeFqn = "kotlin.Function0"
                    it.lambdaReturnTypeFqn = "kotlin.Unit"
                  }
                  .build(),
                TargetParameter.Builder("modifier", "Modifier")
                  .also { it.hasDefault = true }
                  .build(),
                TargetParameter.Builder("content", "() -> Unit")
                  .also { it.composableSlot = true }
                  .build(),
              )
          }
          .build()
      val adapter = Adapter(record)
      val generated =
        TypedAdapterCatalog.generate(
          ComponentRecordFile.Builder(":app", "desktop", listOf(record)).build(),
          UiBuilderCatalogs.CoverSheet("acme", "Acme"),
          UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
          listOf(adapter),
        )
      var clicks = 0
      val registry = canvasAdapterRegistry {
        register(adapter) {
          val click = callback(adapter.click)
          BrandButton(
            value(adapter.label),
            {
              clicks++
              click()
            },
            modifier,
          ) {
            Slot(adapter.content)
          }
        }
      }
      val empty = JsonObject(emptyMap())
      val document =
        UiBuilderDocument(
          schema = "compose-ui-builder-document/v1-candidate",
          id = "typed",
          title = "Typed",
          revision = 0,
          catalogPin = empty,
          environment = empty,
          stateVariables = empty,
          roots = listOf("button"),
          nodes =
            mapOf(
              "button" to
                UiBuilderNode(
                  id = "button",
                  componentId = adapter.id,
                  properties =
                    buildJsonObject {
                      put(
                        "label",
                        buildJsonObject {
                          put("type", "string")
                          put("value", "From generated catalog")
                        },
                      )
                    },
                  slots = mapOf("content" to listOf("child")),
                ),
              "child" to
                UiBuilderNode(
                  id = "child",
                  componentId = "m3/text",
                  properties =
                    buildJsonObject {
                      put(
                        "text",
                        buildJsonObject {
                          put("type", "string")
                          put("value", "Real slot child")
                        },
                      )
                    },
                ),
            ),
        )
      setContent {
        UiBuilderSurface(
          document,
          catalogComponentIds = setOf(adapter.id, "m3/text"),
          canvasAdapterIds =
            generated.catalog.statusSemantics.components.mapValues { it.value.canvas!! },
          canvasAdapterRegistry = registry,
        )
      }
      onNodeWithText("From generated catalog").assertExists()
      onNodeWithText("Real slot child").assertExists()
      onNodeWithText("From generated catalog").performClick()
      runOnIdle { assertEquals(1, clicks) }
    }
}
