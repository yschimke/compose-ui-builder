package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Five components the renderer and the Compose exporter have always supported, and no catalog
 * declared.
 *
 * `m3/center-aligned-top-app-bar`, `m3/list-item`, `m3/primary-tab-row`, `m3/tab` and
 * `shape/colour-dot` had a renderer branch, an emitter, an entry in the exporter's field table —
 * and no capability. The palette could not offer them, the inspector had nothing to show for them,
 * and a document that used them was `UNKNOWN_COMPONENT` to the validator. The checked-in Confetti
 * design is exactly such a document, pinned to this very catalog, which is the test below that
 * would have been most surprising to whoever wrote the fixture.
 */
class UnadvertisedComponentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val confetti =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/confetti-schedule-operations-v1.json")).jsonObject
      )
      .document

  /**
   * The Confetti design pins `m3-catalog` and names five components that catalog did not declare,
   * so every node using one was `UNKNOWN_COMPONENT` — a whole screen the validator could say
   * nothing useful about.
   *
   * Scoped to those five rather than to the document, because the fixture has other, older
   * mismatches this change does not touch: `layout/lazy-column` gets an `initialIndex` it does not
   * declare and no `scrollStateKey` it requires, `m3/surface` gets a `sticky`, and a filter chip's
   * `selected` carries a `stateEquals` object the property's declared types do not admit. Those are
   * separate gaps in separate components; folding them in here would hide what this test is for.
   */
  @Test
  fun `the Confetti design's use of the five now checks out against the catalog it pins`() {
    assertEquals("m3-catalog", confetti.catalogPin.getValue("systemId").jsonPrimitive.content)

    val coverage = CapabilityValidator(catalog).coverage(confetti)
    assertEquals(emptySet(), coverage.missingComponentIds)
    val issues =
      CapabilityValidator(catalog).validate(confetti).issues.filter {
        it.componentId in UNADVERTISED
      }
    assertEquals(emptyList(), issues)
    // And the design really does use all five, so the assertion above is not vacuous.
    assertEquals(
      UNADVERTISED.toSet(),
      confetti.nodes.values.map { it.componentId }.toSet() intersect UNADVERTISED.toSet(),
    )
  }

  @Test
  fun `each of the five is offered by the palette`() {
    val offered = reducer.catalogItems("").map { it.componentId }.toSet()

    UNADVERTISED.forEach { assertTrue(it in offered, "$it is still not in the palette") }
  }

  @Test
  fun `each of the five inserts into a valid document`() {
    UNADVERTISED.forEach { componentId ->
      val inserted = insert(componentId)
      assertEquals(
        emptyList(),
        CapabilityValidator(catalog).validate(inserted.first.document).issues,
        componentId,
      )
    }
  }

  @Test
  fun `a top app bar arrives titled and drops into a scaffold's top bar`() {
    val inserted = insert("m3/center-aligned-top-app-bar")
    val bar = inserted.first.document.nodes.getValue(inserted.second)

    assertEquals("Title", inserted.text(bar.slots.getValue("title").single()))
    // The trait is what makes it droppable where a top bar goes, and until now `m3/search-bar` was
    // the only component in the catalog that carried it — so a scaffold's `topBar` could hold a
    // search field or nothing.
    assertTrue("TopBar" in catalog.componentsById.getValue("m3/center-aligned-top-app-bar").traits)
  }

  @Test
  fun `a tab row arrives with three tabs and the first one selected`() {
    val inserted = insert("m3/primary-tab-row")
    val row = inserted.first.document.nodes.getValue(inserted.second)
    val tabs = row.slots.getValue("tabs")

    assertEquals(3, tabs.size)
    assertEquals("0", row.stringProperty("selectedIndex"))
    assertEquals(
      listOf("true", "false", "false"),
      tabs.map { inserted.first.document.nodes.getValue(it).stringProperty("selected") },
    )
    assertEquals(
      listOf("Tab 1", "Tab 2", "Tab 3"),
      tabs.map { tab ->
        inserted.text(inserted.first.document.nodes.getValue(tab).slots.getValue("text").single())
      },
    )
  }

  /** A dot with the catalog's neutral defaults is transparent and zero across. */
  @Test
  fun `a colour dot arrives visible`() {
    val inserted = insert("shape/colour-dot")
    val dot = inserted.first.document.nodes.getValue(inserted.second)

    assertEquals("#FF6750A4", dot.stringProperty("color"))
    assertEquals("8", dot.stringProperty("diameterDp"))
  }

  @Test
  fun `a list item arrives with a headline over supporting text`() {
    val inserted = insert("m3/list-item")
    val item = inserted.first.document.nodes.getValue(inserted.second)

    assertEquals("List item", inserted.text(item.slots.getValue("headline").single()))
    assertEquals("Supporting text", inserted.text(item.slots.getValue("supporting").single()))
  }

  /**
   * The three values the canvas drew and the export dropped.
   *
   * Advertising a component means its properties are authorable, and a property the inspector
   * offers and the export discards is the failure this repository keeps writing down. All three
   * were in that state on components nobody could insert, so nobody found out.
   */
  @Test
  fun `the export now carries the colours, the accent bar and the selected tab`() {
    val source =
      CapabilityComposeCodeExporter.export(showcase(selectedTabIndex = 1), catalog).requireSource()

    assertTrue(
      "centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer" in
        source,
      source,
    )
    assertTrue("scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()" in source, source)
    assertTrue(".drawBehind { drawRect(Color(0xFF00C853)" in source, source)
    // A hard-coded zero was right by accident on every design that opens on its first tab. The
    // second tab selected is the case that was not.
    assertTrue("selectedTabIndex = 1" in source, source)
  }

  /**
   * A column holding one of each of the five, built by the reducer and then edited the way an
   * operator would.
   *
   * Assembled here rather than taken from the Confetti fixture because that document has unrelated
   * mismatches (see above) and the export refuses a document the validator rejects — a refusal
   * about a lazy column's missing scroll key would say nothing about a top app bar's colours.
   */
  private fun showcase(selectedTabIndex: Int): UiBuilderDocument {
    var state = reducer.initial(jetcaster, selectedNodeId = "main-background")
    val edits =
      listOf(
        "m3/center-aligned-top-app-bar" to
          listOf(
            "containerColor" to "surfaceContainer",
            "scrolledContainerColor" to "surfaceContainerHigh",
            "scrollBehavior" to "enterAlways",
          ),
        "m3/list-item" to listOf("startAccentColor" to "#FF00C853"),
        "m3/primary-tab-row" to listOf("selectedIndex" to selectedTabIndex.toString()),
        "shape/colour-dot" to emptyList(),
      )
    edits.forEach { (componentId, properties) ->
      val selected = reducer.reduce(state, UiBuilderEditorEvent.SelectNode("main-background"))
      val target = requireNotNull(reducer.dropTarget(selected, componentId)) { componentId }
      val inserted =
        reducer.reduce(selected, UiBuilderEditorEvent.InsertComponent(componentId, target))
      val nodeId = assertNotNull(inserted.selectedNodeId)
      state =
        properties.fold(inserted) { edited, (name, value) ->
          reducer.reduce(edited, UiBuilderEditorEvent.CommitProperty(nodeId, name, value))
        }
    }
    return state.document
  }

  private fun Pair<UiBuilderEditorState, String>.text(nodeId: String): String =
    first.document.nodes.getValue(nodeId).stringProperty("text")

  private fun insert(componentId: String): Pair<UiBuilderEditorState, String> {
    val initial = reducer.initial(jetcaster, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, componentId)) { componentId }
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(componentId, target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, "$componentId: ${inserted.lastOutcome}")
    return inserted to assertNotNull(inserted.selectedNodeId)
  }

  private fun UiBuilderNode.stringProperty(name: String): String =
    (properties.getValue(name) as JsonObject).getValue("value").jsonPrimitive.content

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    val UNADVERTISED =
      listOf(
        "m3/center-aligned-top-app-bar",
        "m3/list-item",
        "m3/primary-tab-row",
        "m3/tab",
        "shape/colour-dot",
      )
  }
}
