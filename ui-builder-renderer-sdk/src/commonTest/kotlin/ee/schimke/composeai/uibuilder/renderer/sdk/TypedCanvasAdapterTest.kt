package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.Modifier
import ee.schimke.composeai.discovery.AdapterObjectCodec
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
                put("type", "bool")
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
  fun `preparation checks unused properties and caches successfully decoded values`() {
    val adapter = Adapter()
    val bad =
      TypedCanvasNodeScope(
        adapter,
        scope(
          buildJsonObject {
            put(
              "checked",
              buildJsonObject {
                put("type", "bool")
                put("value", "true")
              },
            )
          }
        ),
      )
    assertEquals(1, bad.prepare().size)
    val good = TypedCanvasNodeScope(adapter, scope())
    assertEquals(emptyList(), good.prepare())
    assertEquals("Toggle", good.value(adapter.label))
    assertEquals(false, good.value(adapter.checked))
  }

  @Test
  fun `integer handles refuse hand authored number and state wrappers`() {
    data class CountProps(val count: Int)
    val definition =
      object :
        TypedComponentAdapter<CountProps>(
          "acme/count",
          record()
            .newBuilder()
            .also {
              it.parameters =
                listOf(
                  TargetParameter.Builder("count", "Int").also { it.typeFqn = "kotlin.Int" }.build()
                )
            }
            .build(),
        ) {
        val count = property(CountProps::count, AdapterValueCodecs.Int, 3)
      }
    definition.freeze()
    for (type in listOf("number", "state")) {
      val typed =
        TypedCanvasNodeScope(
          definition,
          scope(
            buildJsonObject {
              put(
                "count",
                buildJsonObject {
                  put("type", type)
                  put("value", "3.0")
                },
              )
            }
          ),
        )
      assertEquals(1, typed.prepare().size)
      assertFailsWith<IllegalArgumentException> { typed.value(definition.count) }
    }
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

  private data class Contact(val name: String, val unread: Int, val tags: List<String>)

  private data class InboxProps(val contacts: List<Contact>, val pinned: Contact)

  private class InboxAdapter : TypedComponentAdapter<InboxProps>("acme/inbox", record()) {
    val contacts = property(InboxProps::contacts, AdapterValueCodecs.list(CONTACT), emptyList())
    val pinned = property(InboxProps::pinned, CONTACT, Contact("Nobody", 0, emptyList()))
  }

  private fun inbox(properties: String) =
    InboxAdapter().let { adapter ->
      adapter to
        TypedCanvasNodeScope(adapter, scope(Json.parseToJsonElement(properties).jsonObject))
    }

  @Test
  fun `a list of objects decodes through the codec's nested wrappers`() {
    val (adapter, canvas) =
      inbox(
        """
        {"contacts": {"type": "list", "values": [
          {"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"},
            "tags": {"type": "list", "values": [{"type": "string", "value": "vip"}]}}},
          {"type": "object", "fields": {
            "name": {"type": "string", "value": "Grace"},
            "unread": {"type": "int", "value": 2}}}]}}
        """
      )
    assertEquals(emptyList(), canvas.prepare())
    assertEquals(
      listOf(Contact("Ada", 0, listOf("vip")), Contact("Grace", 2, emptyList())),
      canvas.value(adapter.contacts),
    )
    // Absent, the object property is its declared default, as a scalar is.
    assertEquals(Contact("Nobody", 0, emptyList()), canvas.value(adapter.pinned))
  }

  @Test
  fun `an object property decodes its fields, nested lists included`() {
    val (adapter, canvas) =
      inbox(
        """
        {"pinned": {"type": "object", "fields": {
          "name": {"type": "string", "value": "Linus"},
          "unread": {"type": "int", "value": 7},
          "tags": {"type": "list", "values": [
            {"type": "string", "value": "a"}, {"type": "string", "value": "b"}]}}}}
        """
      )
    assertEquals(Contact("Linus", 7, listOf("a", "b")), canvas.value(adapter.pinned))
    assertEquals(emptyList(), canvas.value(adapter.contacts))
  }

  @Test
  fun `a malformed nested value fails with its path instead of drawing a default`() {
    for (contacts in
      listOf(
        // An unknown field.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"}, "age": {"type": "int", "value": 3}}}]}""",
        // A required field missing.
        """{"type": "list", "values": [{"type": "object", "fields": {}}]}""",
        // A nested scalar of the wrong type.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"},
            "tags": {"type": "list", "values": [{"type": "int", "value": 1}]}}}]}""",
        // A list stored as a bare array, or as a scalar literal.
        """[]""",
        """{"type": "list", "value": []}""",
      )) {
      val (adapter, canvas) = inbox("""{"contacts": $contacts}""")
      val problems = canvas.prepare()
      assertEquals(1, problems.size, contacts)
      val failure = assertFailsWith<IllegalArgumentException> { canvas.value(adapter.contacts) }
      assertTrue(failure.message.orEmpty().startsWith("acme/inbox.contacts"), failure.message)
    }
  }

  @Test
  fun `a list or object property is literal only`() {
    val (adapter, canvas) =
      inbox("""{"contacts": {"type": "state", "variable": "rows", "value": "[]"}}""")
    assertFailsWith<IllegalArgumentException> { canvas.value(adapter.contacts) }
  }

  companion object {
    private val CONTACT: AdapterObjectCodec<Contact> =
      AdapterValueCodecs.obj("acme.Contact") {
        val name = field(Contact::name, AdapterValueCodecs.String)
        val unread = field(Contact::unread, AdapterValueCodecs.Int, default = 0)
        val tags =
          field(Contact::tags, AdapterValueCodecs.list(AdapterValueCodecs.String), emptyList())
        construct { Contact(name = it[name], unread = it[unread], tags = it[tags]) }
      }

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
              TargetParameter.Builder("onClick", "() -> Unit")
                .also {
                  it.typeFqn = "kotlin.Function0"
                  it.lambdaReturnTypeFqn = "kotlin.Unit"
                }
                .build(),
              TargetParameter.Builder("onCheckedChange", "(Boolean) -> Unit")
                .also {
                  it.typeFqn = "kotlin.Function1"
                  it.lambdaReturnTypeFqn = "kotlin.Unit"
                }
                .build(),
            )
        }
        .build()
  }
}
