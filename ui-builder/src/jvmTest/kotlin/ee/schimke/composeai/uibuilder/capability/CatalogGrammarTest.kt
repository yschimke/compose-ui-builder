package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderNode
import ee.schimke.composeai.uibuilder.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The catalog read as a grammar: components are the nonterminals, slots the productions, roles and
 * traits the sorts. A catalog that fails one of these is one the editor cannot fully use — a slot
 * nothing fits, a component nothing can hold, a subtree that can never close — and none of it needs
 * a document to show up, so it is checked here on the catalog alone.
 *
 * The acceptance table pins [SlotCapability.accepts] to `slot-acceptance-v1.json`, which the
 * runtime's `SlotAcceptanceTest` pins its own copy of the rule to, over the same catalog. The two
 * modules cannot see each other, so the table is how they are held to one answer.
 */
class CatalogGrammarTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))

  @Test
  fun `every required slot accepts at least one component`() {
    val unfillable =
      catalog.components.flatMap { component ->
        component.slots
          .filter { slot -> slot.cardinality.min > 0 }
          .filter { slot -> catalog.components.none(slot::accepts) }
          .map { slot -> "${component.componentId}.${slot.name}" }
      }
    assertEquals(emptyList(), unfillable)
  }

  @Test
  fun `every component closes with a finite subtree`() {
    // Least fixed point: a component is productive once every required slot accepts something
    // already productive. Anything left over can only be filled by itself, forever.
    val productive = mutableSetOf<String>()
    var changed = true
    while (changed) {
      changed = false
      catalog.components
        .filter { it.componentId !in productive }
        .filter { component ->
          component.slots.all { slot ->
            slot.cardinality.min == 0 ||
              catalog.components.any { slot.accepts(it) && it.componentId in productive }
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
    )
  }

  @Test
  fun `every component is accepted by some slot`() {
    val orphaned =
      catalog.components
        .filter { component ->
          catalog.components.none { parent -> parent.slots.any { it.accepts(component) } }
        }
        .map { it.componentId }
    assertEquals(emptyList(), orphaned)
  }

  @Test
  fun `every trait a slot names is carried by some component`() {
    // A slot naming a trait nothing has is a slot the palette can never fill. Two were: the
    // snackbar host's `SnackbarContent` and the search bar's `SearchResults`.
    val carried = catalog.components.flatMap { it.traits }.toSet()
    val dangling =
      catalog.components.flatMap { component ->
        component.slots.flatMap { slot ->
          slot.acceptedTraits
            .filter { it != "AnyContent" && it !in carried }
            .map { "${component.componentId}.${slot.name}: $it" }
        }
      }
    assertEquals(emptyList(), dangling)
  }

  @Test
  fun `the acceptance table is the committed one`() {
    val expected =
      Json.parseToJsonElement(resource("/slot-acceptance-v1.json"))
        .jsonObject
        .getValue("catalogs")
        .jsonObject
        .getValue("m3-catalog")
        .jsonObject
        .mapValues { (_, accepted) -> accepted.jsonArray.map { it.jsonPrimitive.content } }
    assertEquals(expected, acceptanceTable(catalog))
  }

  @Test
  fun `a scaffold's top bar takes an app bar or a layout primitive and not a grid`() {
    val topBar = catalog.componentsById.getValue("layout/scaffold").slotsByName.getValue("topBar")
    assertEquals(
      listOf(
        "layout/box",
        "layout/column",
        "layout/row",
        "m3/center-aligned-top-app-bar",
        "m3/search-bar",
      ),
      catalog.components.filter(topBar::accepts).map { it.componentId },
    )
  }

  /**
   * The document from `/ui-builder/m3-catalog/a`: a lazy grid in the scaffold's top bar, holding
   * cards, a text and icon buttons. The grid is a `Container`, which the old rule took as enough;
   * now the missing `TopBar` trait is the finding. The grid's *own* children are fine — a lazy
   * grid's items are an unconstrained lambda in Compose, and the catalog now says so.
   */
  @Test
  fun `a lazy grid in a top bar is an incompatible slot child`() {
    val issues = CapabilityValidator(catalog).validate(gridInTopBar()).issues
    assertEquals(
      listOf(CapabilityIssueCode.INCOMPATIBLE_SLOT_CHILD to "scaffold"),
      issues.map { it.code to it.nodeId },
      issues.joinToString("\n") { it.message },
    )
  }

  private fun gridInTopBar(): UiBuilderDocument {
    val fixture =
      Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
    val base = UiBuilderReducer.replay(fixture).document
    val text = base.nodes.getValue("search-placeholder").copy(id = "text", slots = emptyMap())
    val icon = base.nodes.getValue("search-leading-icon").copy(id = "icon", slots = emptyMap())
    val nodes =
      listOf(
        UiBuilderNode(
          id = "scaffold",
          componentId = "layout/scaffold",
          slots = mapOf("topBar" to listOf("grid"), "content" to listOf("body")),
        ),
        UiBuilderNode(id = "body", componentId = "layout/box"),
        UiBuilderNode(
          id = "grid",
          componentId = "layout/lazy-grid",
          properties = base.nodes.getValue("discover-grid").properties,
          slots = mapOf("items" to listOf("card", "text", "button")),
        ),
        UiBuilderNode(
          id = "card",
          componentId = "m3/card",
          slots = mapOf("content" to listOf("card-text")),
        ),
        text.copy(id = "card-text"),
        text,
        UiBuilderNode(
          id = "button",
          componentId = "m3/icon-button",
          slots = mapOf("content" to listOf("icon")),
        ),
        icon,
      )
    return base.copy(roots = listOf("scaffold"), nodes = nodes.associateBy { it.id })
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}

/** For each slot, the sorted component ids it accepts, keyed `component.slot`, sorted. */
internal fun acceptanceTable(catalog: CapabilityCatalog): Map<String, List<String>> =
  catalog.components
    .flatMap { component ->
      component.slots.map { slot ->
        "${component.componentId}.${slot.name}" to
          catalog.components.filter(slot::accepts).map { it.componentId }.sorted()
      }
    }
    .sortedBy { it.first }
    .toMap()
