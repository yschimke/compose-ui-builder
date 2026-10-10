package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.Modifier
import ee.schimke.composeai.discovery.AdapterValueCodecs
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.discovery.TypedComponentAdapter
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TypedCanvasAdapterTest {
  private data class Props(
    val label: String,
    val checked: Boolean,
    val onClick: () -> Unit,
    val onCheckedChange: (Boolean) -> Unit,
  )

  private class Adapter : TypedComponentAdapter<Props>("acme/toggle", record()) {
    val label = property(Props::label, AdapterValueCodecs.String, "Toggle")
    val checked = property(Props::checked, AdapterValueCodecs.Boolean, false)
    val click = event(Props::onClick)
    val change = stateChange(Props::onCheckedChange, checked)
  }

  private fun scope(
    properties: JsonObject = JsonObject(emptyMap()),
    events: MutableList<String> = mutableListOf(),
    updates: MutableList<Pair<String, String?>> = mutableListOf(),
  ) =
    CanvasNodeScope(
      node = UiBuilderNode(id = "toggle", componentId = "acme/toggle", properties = properties),
      modifier = Modifier,
      mode = CanvasMode.Device,
      renderSlot = { _, _ -> },
      renderItems = { _, _ -> },
      countItems = { 0 },
      renderItem = { _, _, _ -> },
      dispatchEvent = events::add,
      updateState = { key, value -> updates += key to value },
      recordText = {},
    )

  @Test
  fun `typed values preserve defaults and resolved preview state`() {
    val adapter = Adapter()
    val canvas =
      TypedCanvasNodeScope(
        adapter,
        scope(
          buildJsonObject {
            put(
              "checked",
              buildJsonObject {
                put("type", "state")
                put("variable", "checkedState")
                put("value", "true")
              },
            )
          }
        ),
      )
    assertEquals("Toggle", canvas.value(adapter.label))
    assertEquals(true, canvas.value(adapter.checked))
  }

  @Test
  fun `malformed literals and unresolved bindings fail instead of drawing defaults`() {
    val adapter = Adapter()
    val malformed =
      TypedCanvasNodeScope(
        adapter,
        scope(
          buildJsonObject {
            put(
              "checked",
              buildJsonObject {
                put("type", "boolean")
                put("value", "true")
              },
            )
          }
        ),
      )
    assertFailsWith<IllegalArgumentException> { malformed.value(adapter.checked) }
    val unresolved =
      TypedCanvasNodeScope(
        adapter,
        scope(
          buildJsonObject {
            put(
              "checked",
              buildJsonObject {
                put("type", "state")
                put("variable", "checkedState")
              },
            )
          }
        ),
      )
    assertFailsWith<IllegalArgumentException> { unresolved.value(adapter.checked) }
  }

  @Test
  fun `typed callbacks use the existing event and two way state interpreter`() {
    val adapter = Adapter()
    val events = mutableListOf<String>()
    val updates = mutableListOf<Pair<String, String?>>()
    val canvas =
      TypedCanvasNodeScope(
        adapter,
        scope(
          buildJsonObject {
            put(
              "checked",
              buildJsonObject {
                put("type", "state")
                put("variable", "checkedState")
                put("value", false)
              },
            )
          },
          events,
          updates,
        ),
      )
    canvas.callback(adapter.click)()
    canvas.callback(adapter.change)(true)
    assertEquals(listOf("click", "checkedChange"), events)
    assertEquals(listOf<Pair<String, String?>>("checkedState" to "true"), updates)
  }

  @Test
  fun `handles from another instance are rejected even with identical model and names`() {
    val adapter = Adapter()
    val other = Adapter()
    val canvas = TypedCanvasNodeScope(adapter, scope())
    assertFailsWith<IllegalArgumentException> { canvas.value(other.label) }
    assertFailsWith<IllegalArgumentException> { canvas.callback(other.click) }
    assertFailsWith<IllegalArgumentException> { canvas.callback(other.change) }
  }

  companion object {
    private fun record() =
      ComponentRecord.Builder(
          ":app/acme.ToggleKt.Toggle",
          ComponentSymbol.Builder("acme.ToggleKt", "acme.Toggle", "Toggle", ComponentOrigin.PROJECT)
            .build(),
        )
        .also { b ->
          b.signatureKnown = true
          b.parameters =
            listOf(
              TargetParameter.Builder("label", "String")
                .also { it.typeFqn = "kotlin.String" }
                .build(),
              TargetParameter.Builder("checked", "Boolean")
                .also { it.typeFqn = "kotlin.Boolean" }
                .build(),
              TargetParameter.Builder("onClick", "() -> Unit").build(),
              TargetParameter.Builder("onCheckedChange", "(Boolean) -> Unit").build(),
            )
        }
        .build()
  }
}
