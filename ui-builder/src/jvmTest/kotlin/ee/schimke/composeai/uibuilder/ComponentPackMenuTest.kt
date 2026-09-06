package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A component pack in the editor: off until switched on, shelved under its own name, and readable
 * by the code pane through the record projected back from its capabilities.
 *
 * The catalog here is the packaged M3 one with a two-component `confetti-mobile` pack merged in the
 * way the runtime merges one, so the test is about what the editor does with the declaration rather
 * than about the merge.
 */
class ComponentPackMenuTest {
  private val catalog =
    CapabilityCatalogParser.parse(withConfettiPack(resource("/m3-catalog-capabilities-v1.json")))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `the catalog reads its platform and its packs off the declaration`() {
    assertEquals(UiBuilderCatalogPlatform.MOBILE, catalog.platform)
    val pack = catalog.componentPacks.packs.single()
    assertEquals("confetti-mobile", pack.id)
    assertEquals("Confetti Mobile", pack.label)
    assertEquals(
      setOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
      catalog.nativeOnlyComponentIds,
    )
  }

  @Test
  fun `a pack's shelf is absent until it is switched on, and search does not find it either`() {
    val off = reducer.initial(document)
    assertTrue(reducer.catalogRows(off).none { it.isPackRow() })
    assertTrue(
      reducer.catalogRows(off.copy(catalogQuery = "session")).none { it.isPackRow() },
      "search must not surface what the switch hides",
    )

    val on = reducer.reduce(off, UiBuilderEditorEvent.TogglePack("confetti-mobile"))
    assertEquals(setOf("confetti-mobile"), on.enabledPacks)
    val rows = reducer.catalogRows(on)
    val shelf = assertIs<EditorCatalogRow.Group>(rows.first { it.isPackRow() })
    assertEquals("Confetti Mobile", shelf.name)
    assertEquals(2, shelf.count)
    // After every shelf the catalog declared itself, as the runtime ordered it.
    assertEquals(
      rows.filterIsInstance<EditorCatalogRow.Group>().last().name,
      "Confetti Mobile",
    )
    assertEquals(
      listOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
      rows
        .filterIsInstance<EditorCatalogRow.Component>()
        .filter { it.item.pack == "confetti-mobile" }
        .map { it.item.componentId },
    )

    // Toggling again switches it off; a pack the catalog does not have is ignored.
    assertEquals(
      emptySet(),
      reducer.reduce(on, UiBuilderEditorEvent.TogglePack("confetti-mobile")).enabledPacks,
    )
    assertEquals(
      on.enabledPacks,
      reducer.reduce(on, UiBuilderEditorEvent.TogglePack("jetnews")).enabledPacks,
    )
    assertEquals(
      setOf("confetti-mobile"),
      reducer
        .reduce(off, UiBuilderEditorEvent.SetEnabledPacks(setOf("confetti-mobile", "jetnews")))
        .enabledPacks,
    )
  }

  @Test
  fun `the setting survives the document changing underneath it`() {
    val on =
      reducer.reduce(reducer.initial(document), UiBuilderEditorEvent.TogglePack("confetti-mobile"))
    assertEquals(setOf("confetti-mobile"), reducer.reconciled(on, document).enabledPacks)
  }

  @Test
  fun `the code pane prints a pack component's call site from the projected record`() {
    val grid = document.nodes.getValue("discover-grid")
    val withPack =
      document.copy(
        nodes =
          document.nodes +
            ("session" to
              UiBuilderNode(
                id = "session",
                componentId = "confetti-mobile/session-card",
                properties =
                  buildJsonObject {
                    put(
                      "title",
                      buildJsonObject {
                        put("type", "string")
                        put("value", "Compose everywhere")
                      },
                    )
                  },
              )) +
            ("discover-grid" to
              grid.copy(slots = grid.slots + ("items" to grid.slots.getValue("items") + "session")))
      )

    val records = catalog.packComponentRecords()
    assertEquals(
      listOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
      records.map { it.componentIds.single() },
    )
    val card = records.first()
    assertEquals("dev.confetti.ui.SessionCard", card.symbol.callable)
    assertEquals(listOf("title", "content"), card.parameters.map { it.name })
    assertEquals("kotlin.String", card.parameters.first().typeFqn)
    assertTrue(card.parameters.last().composableSlot)

    // The problems panel judges the pack node against the same record, so it is not "no component
    // in this catalog" — the one refusal a pack node must never draw.
    val refusals = reducer.problems(withPack).map { it.message }
    assertTrue(
      refusals.none { "confetti-mobile/session-card" in it && "no component" in it },
      refusals.toString(),
    )
  }

  private fun EditorCatalogRow.isPackRow(): Boolean =
    when (this) {
      is EditorCatalogRow.Group -> name == "Confetti Mobile"
      is EditorCatalogRow.Component -> item.pack != null
      is EditorCatalogRow.Variant -> false
    }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  companion object {
    /** The M3 catalog with a `confetti-mobile` pack merged in, the way the runtime writes one. */
    fun withConfettiPack(source: String): String {
      val root = Json.parseToJsonElement(source).jsonObject
      val statusSemantics = root.getValue("statusSemantics").jsonObject
      val menu = statusSemantics.getValue("componentMenu").jsonObject
      fun component(id: String, name: String, callable: String, container: Boolean) =
        buildJsonObject {
          put("componentId", id)
          put("displayName", name)
          put("role", if (container) "Container" else "Leaf")
          put("traits", buildJsonArray { add(JsonPrimitive("PackContent")) })
          put(
            "slots",
            buildJsonArray {
              if (container)
                add(
                  buildJsonObject {
                    put("name", "content")
                    put("cardinality", buildJsonObject { put("min", 0) })
                    put("ordered", true)
                  }
                )
            },
          )
          put(
            "properties",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("name", "title")
                  put("jsonType", "string")
                  put("required", true)
                }
              )
            },
          )
          put("modifierCapabilities", buildJsonArray { add(JsonPrimitive("padding")) })
          put(
            "wasm",
            buildJsonObject {
              put("platformSupported", false)
              put("adapterStatus", "unsupported")
            },
          )
          put(
            "code",
            buildJsonObject {
              put("symbol", callable)
              put("imports", buildJsonArray { add(JsonPrimitive(callable)) })
            },
          )
        }
      val packComponents =
        listOf(
          component(
            "confetti-mobile/session-card",
            "Session Card",
            "dev.confetti.ui.SessionCard",
            true,
          ),
          component(
            "confetti-mobile/speaker-row",
            "Speaker Row",
            "dev.confetti.ui.SpeakerRow",
            false,
          ),
        )
      val newMenu =
        JsonObject(
          menu +
            ("groupOrder" to
              buildJsonArray {
                menu.getValue("groupOrder").jsonArray.forEach(::add)
                add(JsonPrimitive("Confetti Mobile"))
              }) +
            ("components" to
              JsonObject(
                menu.getValue("components").jsonObject +
                  packComponents.associate {
                    it.getValue("componentId").let { id -> (id as JsonPrimitive).content } to
                      buildJsonObject { put("group", "Confetti Mobile") }
                  }
              ))
        )
      val packs = buildJsonArray {
        add(
          buildJsonObject {
            put("id", "confetti-mobile")
            put("label", "Confetti Mobile")
            put("platform", "mobile")
            put("nativeCatalog", "confetti-mobile")
            put(
              "components",
              buildJsonArray { packComponents.forEach { add(it.getValue("componentId")) } },
            )
          }
        )
      }
      return Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
          root +
            ("statusSemantics" to
              JsonObject(
                statusSemantics + ("componentMenu" to newMenu) + ("componentPacks" to packs)
              )) +
            ("components" to
              buildJsonArray {
                root.getValue("components").jsonArray.forEach(::add)
                packComponents.forEach(::add)
              })
        ),
      )
    }
  }
}
