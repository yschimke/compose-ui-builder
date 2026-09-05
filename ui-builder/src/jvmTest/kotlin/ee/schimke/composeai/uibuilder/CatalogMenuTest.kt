package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The insert panel's tree: the catalog's own families, the components on each, and the variants
 * under a component.
 *
 * These are assertions about a **menu**, which is why they are here rather than left to a render:
 * the shape of the tree is a pure function of the catalog and two sets of open rows, and a
 * screenshot can tell you that a row is missing but not why. The rendering — chevrons, counts,
 * indentation — is what the editor chrome previews are for.
 */
class CatalogMenuTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val state = reducer.initial(document, selectedNodeId = "discover-grid")

  @Test
  fun `the menu table covers this catalog and nothing else`() {
    // The `StarterContent` bargain, for the same reason: a table beside the catalog rather than a
    // field on it (see `ComponentMenu`) only stays true if something checks. A component that
    // arrives in the catalog with no shelf falls back to its kind heading in the product, which is
    // a quiet regression to the panel this replaced — so it fails here instead.
    val known = catalog.components.map { it.componentId }.toSet()

    assertEquals(
      emptyList(),
      known.filter { ComponentMenu.groupOf(it) == null }.sorted(),
      "components with no shelf",
    )
    assertEquals(
      emptyList(),
      (ComponentMenu.componentIds - known).sorted(),
      "shelved components the catalog no longer has",
    )
    assertEquals(
      emptyList(),
      known.mapNotNull(ComponentMenu::groupOf).distinct().filterNot {
        it in ComponentMenu.GROUP_ORDER
      },
      "shelves missing from GROUP_ORDER",
    )
  }

  @Test
  fun `every declared variant property is an enum on the component that names it`() {
    catalog.components.forEach { component ->
      val name = ComponentMenu.variantPropertyOf(component.componentId) ?: return@forEach
      val property = component.propertiesByName[name]
      assertNotNull(property, "${component.componentId} declares no property $name")
      assertTrue(
        property.allowedValues.isNotEmpty(),
        "${component.componentId}.$name is not an enum, so it enumerates no variants",
      )
      assertEquals(
        property.allowedValues.mapNotNull { it.jsonPrimitive.contentOrNull },
        component.menuVariantValues(),
        component.componentId,
      )
    }
  }

  @Test
  fun `a catalog the table says nothing about keeps the kind headings`() {
    // What `wear-m3` and `remote-m3` get, and what this panel was before: a component with no
    // shelf falls back to its kind, so an unstyled catalog is unstyled rather than ungrouped.
    val unknown =
      UiBuilderEditorReducer(
          catalog.copy(
            components =
              catalog.components.map { it.copy(componentId = "unknown/${it.componentId}") }
          )
        )
        .catalogRows(state)
        .filterIsInstance<EditorCatalogRow.Group>()
        .map { it.name }

    assertEquals(
      EditorComponentKind.entries.map { it.label }.filter { it in unknown },
      unknown,
      "an unshelved catalog should read as Scaffolds/Containers/Composables",
    )
  }

  @Test
  fun `families lead their components, in the order the catalog declares`() {
    val rows = reducer.catalogRows(state)
    val groups = rows.filterIsInstance<EditorCatalogRow.Group>().map { it.name }

    // Not alphabetical, and not the id order the fixture is written in: `GROUP_ORDER` is what says
    // a scaffold is the first thing you reach for and a gradient is not.
    assertEquals(ComponentMenu.GROUP_ORDER.filter { it in groups }, groups)
    // The count on a heading is the components under it, so heading and rows cannot disagree.
    rows.filterIsInstance<EditorCatalogRow.Group>().forEach { group ->
      val under =
        rows
          .dropWhile { it !== group }
          .drop(1)
          .takeWhile { it !is EditorCatalogRow.Group }
          .filterIsInstance<EditorCatalogRow.Component>()
      assertEquals(group.count, under.size, group.name)
      under.forEach { assertEquals(group.name, it.item.group) }
    }
  }

  @Test
  fun `collapsing a family hides its components and keeps its count`() {
    val collapsed =
      reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup("Selection")).let(::rows)
    val selection =
      collapsed.filterIsInstance<EditorCatalogRow.Group>().first { it.name == "Selection" }

    assertTrue(selection.count > 1)
    assertEquals(0, componentsUnder(collapsed, "Selection").size)
    // Every other family is untouched — collapsing is one heading's business.
    assertTrue(componentsUnder(collapsed, "Layout").isNotEmpty())
    assertEquals(
      componentsUnder(rows(state), "Selection").size,
      selection.count,
      "a collapsed heading still says how much is behind it",
    )
  }

  @Test
  fun `a card's variants are its catalog values, default first, and only when expanded`() {
    val card = component(rows(state), "m3/card")

    assertEquals(listOf("filled", "elevated", "outlined"), card.item.variants.map { it.value })
    assertEquals(listOf("Filled", "Elevated", "Outlined"), card.item.variants.map { it.label })
    assertEquals(listOf(true, false, false), card.item.variants.map { it.default })
    assertEquals(0, variantRows(rows(state), "m3/card").size)

    val expanded = reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogComponent("m3/card"))

    assertEquals(
      listOf("filled", "elevated", "outlined"),
      variantRows(rows(expanded), "m3/card").map { it.variant.value },
    )
    // One component opens, not the shelf: a Dialog sitting beside the Card stays shut.
    assertEquals(0, variantRows(rows(expanded), "m3/text-field").size)
  }

  @Test
  fun `a component the catalog names no variant property for offers none`() {
    // `m3/icon.iconKey` is an enum of forty-seven icons, and forty-seven rows under Icon is what
    // declaring the variant property rather than guessing at it avoids.
    assertEquals(emptyList(), component(rows(state), "m3/icon").item.variants)
    assertEquals(emptyList(), component(rows(state), "layout/row").item.variants)
  }

  @Test
  fun `adding a variant inserts the component already carrying it`() {
    val outlined = component(rows(state), "m3/card").item.variants.single { it.value == "outlined" }
    val target = assertNotNull(reducer.dropTarget(state, "m3/card"))

    val inserted =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/card", target, outlined))

    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    val node = inserted.document.nodes.getValue(assertNotNull(inserted.selectedNodeId))
    assertEquals("m3/card", node.componentId)
    assertEquals(
      "outlined",
      node.properties.getValue("variant").jsonObject["value"]?.jsonPrimitive?.content,
    )
    // The variant is a fact about the card, not a replacement for what a card arrives holding:
    // the starter content is still there.
    assertTrue(node.slots.getValue("content").isNotEmpty(), "outlined card arrived empty")
  }

  @Test
  fun `adding without a variant leaves the choice to the catalog, and the default row records it`() {
    val target = assertNotNull(reducer.dropTarget(state, "m3/card"))

    val plain = reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/card", target))
    val explicit =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent(
          "m3/card",
          target,
          component(rows(state), "m3/card").item.variants.single { it.default },
        ),
      )

    // `variant` is optional, so the row's Add writes no opinion at all and the renderer applies the
    // catalog's first allowed value — which is exactly what this insert did before variants
    // existed, and what the Default variant row then writes down. Picking the default explicitly
    // is a choice, and a choice a collaborator can read is worth the one property.
    assertEquals(
      null,
      inserted(plain).properties["variant"],
      "a plain Add started writing a property it never wrote",
    )
    assertEquals(
      "filled",
      inserted(explicit).properties.getValue("variant").jsonObject["value"]?.jsonPrimitive?.content,
    )
    assertEquals(
      catalog.componentsById.getValue("m3/card").menuVariantValues().first(),
      component(rows(state), "m3/card").item.variants.single { it.default }.value,
    )
  }

  private fun inserted(state: UiBuilderEditorState) =
    state.document.nodes.getValue(assertNotNull(state.selectedNodeId))

  @Test
  fun `a search opens what it matched and does not spend the collapse doing it`() {
    val collapsed = reducer.reduce(state, UiBuilderEditorEvent.ToggleCatalogGroup("Containment"))
    val searching = reducer.reduce(collapsed, UiBuilderEditorEvent.SearchCatalog("outlined"))

    // "outlined" is a variant label and nothing's display name, so the only way a Card is here is
    // by its variants — and the row is open, since a match hidden behind a twisty reads as no
    // match.
    val matched = searching.let(::rows).filterIsInstance<EditorCatalogRow.Component>()
    assertTrue(matched.any { it.item.componentId == "m3/card" }, matched.toString())
    assertTrue(variantRows(rows(searching), "m3/card").isNotEmpty())

    val cleared = reducer.reduce(searching, UiBuilderEditorEvent.SearchCatalog(""))

    assertEquals(0, componentsUnder(rows(cleared), "Containment").size, "the collapse came back")
  }

  @Test
  fun `a component the table does not know joins the shelf its kind names`() {
    // `remote-m3`'s real shape: the two `remote-m3/widget-container-*` scaffolds this table says
    // nothing about, beside the m3 components it does. Their kind label is "Scaffolds", which is
    // also a declared shelf — so they join it rather than opening a second heading beside it, and
    // the panel for that catalog reads as one tree. Nothing arranges that; it falls out of the
    // fallback, which is exactly why it is worth a test.
    val widget =
      catalog.componentsById
        .getValue("layout/scaffold")
        .copy(
          componentId = "remote-m3/widget-container-small",
          displayName = "Wear widget · Small",
        )
    val rows =
      UiBuilderEditorReducer(catalog.copy(components = catalog.components + widget))
        .catalogRows(state)

    assertEquals(
      listOf("Scaffold", "Supporting pane scaffold", "Wear widget · Small"),
      componentsUnder(rows, "Scaffolds").map { it.item.displayName },
    )
    assertEquals(
      emptyList(),
      rows
        .filterIsInstance<EditorCatalogRow.Group>()
        .map { it.name }
        .filter {
          it in EditorComponentKind.entries.map(EditorComponentKind::label) &&
            it !in ComponentMenu.GROUP_ORDER
        },
      "an unshelved component opened a kind heading beside the real shelves",
    )
  }

  @Test
  fun `no two rows of one search answer to the same name`() {
    // `ui-builder-editor.spec.mjs` drags `getByRole("img", {name: "Drag Text"})` after filtering to
    // "Text", and Playwright's strict mode fails on a second match. Button's `text` variant is
    // exactly that second match, which is why the variant rows qualify their names with the
    // component. Every query anyone might type is checked, not just that one — the collision is a
    // property of the panel, not of one string in one spec.
    val queries = catalog.components.flatMap { listOf(it.displayName) + it.menuVariantValues() }
    queries.forEach { query ->
      val filtered = reducer.reduce(state, UiBuilderEditorEvent.SearchCatalog(query))
      val names =
        rows(filtered).mapNotNull {
          when (it) {
            is EditorCatalogRow.Component -> it.item.displayName
            is EditorCatalogRow.Variant -> "${it.variant.label} ${it.componentName}"
            is EditorCatalogRow.Group -> null
          }
        }

      assertEquals(
        names.distinct(),
        names,
        "two draggable rows share a name while filtering to “$query”",
      )
    }
  }

  private fun rows(state: UiBuilderEditorState) = reducer.catalogRows(state)

  private fun componentsUnder(rows: List<EditorCatalogRow>, group: String) =
    rows
      .dropWhile { !(it is EditorCatalogRow.Group && it.name == group) }
      .drop(1)
      .takeWhile { it !is EditorCatalogRow.Group }
      .filterIsInstance<EditorCatalogRow.Component>()

  private fun component(rows: List<EditorCatalogRow>, componentId: String) =
    rows.filterIsInstance<EditorCatalogRow.Component>().single {
      it.item.componentId == componentId
    }

  private fun variantRows(rows: List<EditorCatalogRow>, componentId: String) =
    rows.filterIsInstance<EditorCatalogRow.Variant>().filter {
      it.variant.componentId == componentId
    }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
