package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AdaptiveGridValueV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The server's copy of the slot rule, [slotAccepts], held to the same committed table as the
 * editor's `SlotCapability.accepts` — `docs/design/fixtures/ui-builder/slot-acceptance-v1.json`,
 * which `:ui-builder`'s `CatalogGrammarTest` checks its half of. The two modules cannot reach each
 * other, so this table is the only thing keeping a document the editor accepts from being one the
 * server refuses, or the other way round.
 *
 * The grammar checks run over every enabled catalog, since `remote-m3` and `wear-m3` are built in
 * code rather than read from the fixture and the editor's test never sees them.
 *
 * Regenerate the table after a deliberate catalog change, then read the diff — a slot gaining or
 * losing components is the whole point of reviewing it:
 * ```
 * ./gradlew :ui-builder-runtime:test --tests '*SlotAcceptanceTest*' \
 *   -PuiBuilderSlotAcceptanceUpdate=true --rerun
 * ```
 */
class SlotAcceptanceTest {
  private val catalogs =
    CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds =
          linkedSetOf(
            CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
            CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
            CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
          )
      )
      .listCatalogs()

  @Test
  fun `the acceptance table is the committed one for every enabled catalog`() {
    val actual = catalogs.associate { catalog ->
      catalog.benchmark.catalogSystemId to acceptanceTable(catalog)
    }
    if (System.getProperty(UPDATE_PROPERTY) == "true") {
      TABLE.writeText(render(actual))
    }
    val committed =
      Json.parseToJsonElement(TABLE.readText())
        .jsonObject
        .getValue("catalogs")
        .jsonObject
        .mapValues { (_, slots) ->
          slots.jsonObject.mapValues { (_, accepted) ->
            accepted.jsonArray.map { it.jsonPrimitive.content }
          }
        }
    assertEquals(committed, actual, "regenerate with -P$UPDATE_PROPERTY=true and read the diff")
  }

  @Test
  fun `every required slot accepts at least one component`() {
    catalogs.forEach { catalog ->
      val unfillable =
        catalog.components.flatMap { component ->
          component.slots
            .filter { it.cardinality.min > 0 }
            .filter { slot -> catalog.components.none { slotAccepts(slot, it) } }
            .map { "${component.componentId}.${it.name}" }
        }
      assertEquals(emptyList(), unfillable, catalog.benchmark.catalogSystemId)
    }
  }

  @Test
  fun `every component closes with a finite subtree`() {
    catalogs.forEach { catalog ->
      val productive = mutableSetOf<String>()
      var changed = true
      while (changed) {
        changed = false
        catalog.components
          .filter { it.componentId !in productive }
          .filter { component ->
            component.slots.all { slot ->
              slot.cardinality.min == 0 ||
                catalog.components.any { slotAccepts(slot, it) && it.componentId in productive }
            }
          }
          .forEach {
            productive += it.componentId
            changed = true
          }
      }
      assertEquals(
        emptyList(),
        catalog.components.map { it.componentId }.filterNot(productive::contains),
        catalog.benchmark.catalogSystemId,
      )
    }
  }

  /**
   * A slot naming a trait no component in its catalog carries is a slot the palette can never fill.
   *
   * There are none left. The last two were `remote-m3`'s widget backgrounds, which take `DrawLayer`
   * and `ImageContent` brushes the reviewed subset had no component for; that subset now carries
   * `shape/linear-gradient`, which `RemoteContentEmitter` writes as a `WearWidgetBrush` chain, and
   * `asset/image`, whose export refusal names the bitmap to supply by hand
   * (yschimke/compose-preview-server#428). `shape/radial-gradient` stays out, and the reason is in
   * `remoteM3Catalog`.
   */
  @Test
  fun `every trait a slot names is carried by some component`() {
    val known = emptySet<String>()
    catalogs.forEach { catalog ->
      val carried = catalog.components.flatMap { it.traits }.toSet()
      val dangling =
        catalog.components.flatMap { component ->
          component.slots
            .filter { "${component.componentId}.${it.name}" !in known }
            .flatMap { slot ->
              slot.acceptedTraits
                .filter { it != "AnyContent" && it !in carried }
                .map { "${component.componentId}.${slot.name}: $it" }
            }
        }
      assertEquals(emptyList(), dangling, catalog.benchmark.catalogSystemId)
    }
  }

  /**
   * The document from `/ui-builder/m3-catalog/a`, refused where it is committed, not just drawn.
   */
  @Test
  fun `the server refuses a lazy grid in a scaffold's top bar`() {
    val executor = CurrentM3UiBuilderCatalogExecutor()
    val catalog = executor.listCatalogs().single()
    val document =
      productionDocument()
        .copy(
          roots = listOf("scaffold"),
          nodes =
            linkedMapOf(
              "scaffold" to
                DesignNodeV1(
                  id = "scaffold",
                  componentId = "layout/scaffold",
                  slots = mapOf("topBar" to listOf("grid"), "content" to listOf("body")),
                ),
              "body" to DesignNodeV1(id = "body", componentId = "layout/box"),
              "grid" to
                DesignNodeV1(
                  id = "grid",
                  componentId = "layout/lazy-grid",
                  properties =
                    mapOf(
                      "columns" to AdaptiveGridValueV1(JsonPrimitive(362.0)),
                      "scrollStateKey" to StringValueV1("grid"),
                    ),
                  slots = mapOf("items" to listOf("text")),
                ),
              "text" to productionDocument().nodes.getValue("text"),
            ),
        )
    val issue = executor.validate(document, catalog)
    assertEquals("INCOMPATIBLE_SLOT_CHILD" to "scaffold", issue?.code to issue?.nodeId)

    // The grid by itself, in a slot that takes anything, is a fine document.
    val gridInContent =
      document.copy(
        nodes =
          (document.nodes - "body") +
            ("scaffold" to
              document.nodes.getValue("scaffold").copy(slots = mapOf("content" to listOf("grid"))))
      )
    assertNull(executor.validate(gridInContent, catalog))
  }

  /**
   * `remote-m3`'s widget background is declared `Leaf` + `DrawLayer`/`ImageContent` precisely so
   * that a text cannot be dropped in as a "background" — the declaration's own comment says so.
   * Under the old rule the `Leaf` matched and the text went in.
   */
  @Test
  fun `the server refuses a text as a widget background`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds =
          linkedSetOf(CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)
      )
    val catalog = executor.listCatalogs().single()
    val base = productionDocument()
    val document =
      base.copy(
        catalogPin =
          base.catalogPin.copy(
            systemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
            catalogRevision = catalog.benchmark.catalogRevision,
            nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
          ),
        roots = listOf("widget"),
        nodes =
          linkedMapOf(
            "widget" to
              DesignNodeV1(
                id = "widget",
                componentId = "remote-m3/widget-container-small",
                slots = mapOf("background" to listOf("text"), "content" to emptyList()),
              ),
            "text" to base.nodes.getValue("text"),
          ),
      )
    val issue = executor.validate(document, catalog)
    assertEquals("INCOMPATIBLE_SLOT_CHILD" to "widget", issue?.code to issue?.nodeId)
  }

  private fun productionDocument(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "slot-acceptance",
      title = "Slot acceptance",
      revision = 0,
      catalogPin =
        CatalogReferenceV1(
          systemId = "m3-catalog",
          catalogRevision = "candidate",
          capabilityDigest = CurrentM3UiBuilderCatalogExecutor.CURRENT_CAPABILITY_DIGEST,
          nativeRuntimeId = "candidate",
        ),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("text"),
      nodes =
        linkedMapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Hello")),
            )
        ),
    )

  private companion object {
    const val UPDATE_PROPERTY = "uiBuilderSlotAcceptanceUpdate"
    val TABLE = File("../docs/design/fixtures/ui-builder/slot-acceptance-v1.json")

    fun acceptanceTable(catalog: CatalogCapabilityV1): Map<String, List<String>> =
      catalog.components
        .flatMap { component ->
          component.slots.map { slot ->
            "${component.componentId}.${slot.name}" to
              catalog.components.filter { slotAccepts(slot, it) }.map { it.componentId }.sorted()
          }
        }
        .sortedBy { it.first }
        .toMap()

    fun render(table: Map<String, Map<String, List<String>>>): String {
      val json = Json { prettyPrint = true }
      val document =
        JsonObject(
          mapOf(
            "schema" to JsonPrimitive("compose-ui-builder-slot-acceptance/v1"),
            "\$comment" to
              JsonPrimitive(
                "Which components each slot of each enabled UI-builder catalog accepts, keyed " +
                  "component.slot. Generated by SlotAcceptanceTest in :ui-builder-runtime and " +
                  "checked by it and by CatalogGrammarTest in :ui-builder, so the server's and " +
                  "the editor's copies of the slot rule cannot drift. Regenerate with " +
                  "./gradlew :ui-builder-runtime:test --tests '*SlotAcceptanceTest*' " +
                  "-PuiBuilderSlotAcceptanceUpdate=true --rerun, then read the diff."
              ),
            "catalogs" to
              JsonObject(
                table.toSortedMap().mapValues { (_, slots) ->
                  JsonObject(
                    slots.mapValues { (_, accepted) -> JsonArray(accepted.map(::JsonPrimitive)) }
                  )
                }
              ),
          )
        )
      return json.encodeToString(JsonObject.serializer(), document) + "\n"
    }
  }
}
