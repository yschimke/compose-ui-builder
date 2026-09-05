package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import ee.schimke.composeai.uibuilder.capability.SlotCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The table in [StarterContent] against the catalog it seeds, and the inserts it produces.
 *
 * The runtime drops a starter entry that does not fit rather than failing the insert — which is the
 * right behaviour in the product and would make a stale table entry invisible. These tests are the
 * other half of that trade: every entry is checked here, so a renamed slot or a dropped icon key
 * fails a build rather than quietly reverting a component to arriving empty.
 */
class StarterContentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `every starter entry names a component slot and value the catalog declares`() {
    // `wear-m3` is synthesised at runtime by `:ui-builder-runtime` from this same M3 fixture, and
    // this module may not depend on that one — so its components are not in the catalog parsed
    // here and cannot be checked against it. The pairing is not lost, it is split: this test
    // checks that a Wear seed at least names a component the generator publishes, and
    // `WearM3ScreenCatalogTest` over in the runtime checks that the palette offers exactly those
    // ids. A seed for a Wear component nobody publishes fails here; one for a component the
    // palette does not offer fails there.
    StarterContent.componentIds
      .filter { it.startsWith("wear-m3/") }
      .forEach { componentId ->
        assertTrue(
          componentId in WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS ||
            componentId in
              setOf(
                WearScreenCodeExporter.TEXT,
                WearScreenCodeExporter.CARD,
                WearScreenCodeExporter.BUTTON,
                WearScreenCodeExporter.LIST_HEADER,
              ),
          "$componentId is seeded and no Wear generator writes it",
        )
      }
    StarterContent.componentIds
      .filterNot { it.startsWith("wear-m3/") }
      .forEach { componentId ->
        val component =
          assertNotNull(catalog.componentsById[componentId], "$componentId is not in the catalog")
        StarterContent.forComponent(componentId).forEach { (slotName, children) ->
          val slot =
            assertNotNull(
              component.slotsByName[slotName],
              "$componentId does not declare slot $slotName",
            )
          assertCheckedSeed(component, slot, children)
        }
      }
  }

  private fun assertCheckedSeed(
    parent: ComponentCapability,
    slot: SlotCapability,
    children: List<StarterNode>,
  ) {
    val where = "${parent.componentId}.${slot.name}"
    assertTrue(
      children.size >= slot.cardinality.min,
      "$where seeds fewer children than its minimum",
    )
    slot.cardinality.max?.let {
      assertTrue(children.size <= it, "$where seeds more children than its maximum")
    }
    children.forEach { child ->
      val capability =
        assertNotNull(
          catalog.componentsById[child.componentId],
          "$where seeds ${child.componentId}, which is not in the catalog",
        )
      assertTrue(
        slot.acceptsForTest(capability),
        "$where does not accept ${child.componentId}",
      )
      child.properties.forEach { (name, encoded) ->
        val property =
          assertNotNull(
            capability.propertiesByName[name],
            "${child.componentId} does not declare property $name",
          )
        val value = assertNotNull(encoded["value"], "$name is seeded without a value")
        if (property.allowedValues.isNotEmpty()) {
          assertTrue(
            value in property.allowedValues,
            "${child.componentId}.$name is seeded with a value outside its allowed set",
          )
        }
      }
      child.slots.forEach { (childSlotName, grandchildren) ->
        val childSlot =
          assertNotNull(
            capability.slotsByName[childSlotName],
            "${child.componentId} does not declare slot $childSlotName",
          )
        assertCheckedSeed(capability, childSlot, grandchildren)
      }
    }
  }

  /**
   * The property the whole change exists for: inserting any component in the catalog leaves a
   * document the validator accepts.
   *
   * Every component, not the seeded ones, because starter content changed the shape of every insert
   * — a seed that fits its own slot and breaks the *parent's* cardinality would only show up here.
   */
  @Test
  fun `inserting any catalog component leaves a capability valid document`() {
    val validator = CapabilityValidator(catalog)
    catalog.components.forEach { component ->
      val initial = reducer.initial(document, selectedNodeId = "main-background")
      val target = reducer.dropTarget(initial, component.componentId) ?: return@forEach
      val inserted =
        reducer.reduce(
          initial,
          UiBuilderEditorEvent.InsertComponent(component.componentId, target),
        )
      assertIs<CommandOutcome.Accepted>(
        inserted.lastOutcome,
        "${component.componentId} did not insert: ${inserted.lastOutcome}",
      )
      val issues = validator.validate(inserted.document).issues
      assertTrue(issues.isEmpty(), "${component.componentId} inserted invalid: $issues")
    }
  }

  @Test
  fun `an icon button arrives holding an icon`() {
    val inserted = insert("m3/icon-button")
    val iconId =
      inserted.document.nodes.getValue(inserted.rootId).slots.getValue("content").single()
    val icon = inserted.document.nodes.getValue(iconId)

    assertEquals("m3/icon", icon.componentId)
    assertEquals("favorite", icon.stringProperty("iconKey"))
  }

  /**
   * The insert this change fixes outright rather than improves. `m3/button`'s content slot accepts
   * text and icons alike, and the generic fill tested the icon branch first, so a button arrived
   * holding the first icon in the enum — a clock.
   */
  @Test
  fun `a button arrives reading Button rather than holding an icon`() {
    val inserted = insert("m3/button")
    val labelId =
      inserted.document.nodes.getValue(inserted.rootId).slots.getValue("content").single()
    val label = inserted.document.nodes.getValue(labelId)

    assertEquals("m3/text", label.componentId)
    assertEquals("Button", label.stringProperty("text"))
  }

  @Test
  fun `a card arrives with a title over supporting text`() {
    val inserted = insert("m3/card")
    val columnId =
      inserted.document.nodes.getValue(inserted.rootId).slots.getValue("content").single()
    val lines =
      inserted.document.nodes.getValue(columnId).slots.getValue("children").map {
        inserted.document.nodes.getValue(it).stringProperty("text")
      }

    assertEquals(listOf("Card title", "Supporting text for this card."), lines)
  }

  @Test
  fun `a lazy column arrives with three item cards`() {
    val inserted = insert("layout/lazy-column")
    val items = inserted.document.nodes.getValue(inserted.rootId).slots.getValue("items")

    assertEquals(3, items.size)
    items.forEach { assertEquals("m3/card", inserted.document.nodes.getValue(it).componentId) }
    assertEquals(
      listOf("List item one", "List item two", "List item three"),
      items.map { itemId ->
        val cardContent =
          inserted.document.nodes.getValue(itemId).slots.getValue("content").single()
        inserted.document.nodes.getValue(cardContent).stringProperty("text")
      },
    )
  }

  /**
   * A seed reached through a required-slot fill rather than through its own palette insert: nothing
   * in the table mentions `m3/search-bar`, and the search field its `inputField` minimum resolves
   * to carries the placeholder and magnifier.
   */
  @Test
  fun `a search bar arrives with a placeholder and a leading icon`() {
    val inserted = insert("m3/search-bar")
    val fieldId =
      inserted.document.nodes.getValue(inserted.rootId).slots.getValue("inputField").single()
    val field = inserted.document.nodes.getValue(fieldId)

    assertEquals(
      "Search",
      inserted.document.nodes
        .getValue(field.slots.getValue("placeholder").single())
        .stringProperty("text"),
    )
    assertEquals(
      "search",
      inserted.document.nodes
        .getValue(field.slots.getValue("leadingIcon").single())
        .stringProperty("iconKey"),
    )
  }

  /** The promise the seed makes: everything it adds is an ordinary node that can be deleted. */
  @Test
  fun `a seeded child can be deleted and leaves a valid document`() {
    val inserted = insert("layout/lazy-column")
    val firstItem =
      inserted.document.nodes.getValue(inserted.rootId).slots.getValue("items").first()
    val selected = reducer.reduce(inserted.state, UiBuilderEditorEvent.SelectNode(firstItem))
    val deleted = reducer.reduce(selected, UiBuilderEditorEvent.DeleteSelected)

    assertIs<CommandOutcome.Accepted>(deleted.lastOutcome)
    assertEquals(2, deleted.document.nodes.getValue(inserted.rootId).slots.getValue("items").size)
    assertTrue(CapabilityValidator(catalog).validate(deleted.document).issues.isEmpty())
  }

  /**
   * Wrapping supplies the content itself, so the container it creates is not seeded. A seeded
   * subtree here would be inserted and deleted inside one batch, and the deeper half of it would
   * outlive the sweep that removes the wrap slot's placeholders.
   */
  @Test
  fun `wrapping a selection does not seed starter content`() {
    val initial = reducer.initial(document, selectedNodeId = "main-episode-title")
    val wrapped = reducer.reduce(initial, UiBuilderEditorEvent.WrapSelection("m3/card"))

    assertIs<CommandOutcome.Accepted>(wrapped.lastOutcome)
    val card = wrapped.document.nodes.getValue(assertNotNull(wrapped.selectedNodeId))
    assertEquals(listOf("main-episode-title"), card.slots.getValue("content"))
  }

  /**
   * The batch a seeded insert submits stays far inside `maximumOperationsPerBatch` (256), which is
   * what an atomic insert has to fit in. The bound is deliberately much tighter than the limit: a
   * seed anyone would call typical is a handful of nodes, and one that is not is a table entry to
   * reconsider rather than a batch to enlarge.
   */
  @Test
  fun `no seeded insert exceeds a reviewable number of nodes`() {
    catalog.components.forEach { component ->
      val initial = reducer.initial(document, selectedNodeId = "main-background")
      val target = reducer.dropTarget(initial, component.componentId) ?: return@forEach
      val inserted =
        reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(component.componentId, target))
      val added = inserted.document.nodes.size - document.nodes.size
      assertTrue(added <= 16, "${component.componentId} inserts $added nodes")
    }
  }

  private data class Insertion(val state: UiBuilderEditorState, val rootId: String) {
    val document: UiBuilderDocument
      get() = state.document
  }

  private fun insert(componentId: String): Insertion {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, componentId))
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(componentId, target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    return Insertion(inserted, assertNotNull(inserted.selectedNodeId))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun UiBuilderNode.stringProperty(name: String): String =
    properties.getValue(name).jsonObject.getValue("value").jsonPrimitive.content

  private fun SlotCapability.acceptsForTest(component: ComponentCapability): Boolean =
    "AnyContent" in acceptedTraits ||
      component.role in acceptedRoles ||
      component.traits.any(acceptedTraits::contains)
}
