package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `wear-m3` authoring surface: what it admits, and what it says when asked for something else.
 *
 * The catalog is synthesised from the packaged M3 one rather than shipped as its own resource, the
 * way `remote-m3`'s widget scaffolds are. That is a statement about how much of it is real: two
 * components are Wear's and every other component in it is Material 3 borrowed for the canvas.
 */
class WearM3ScreenCatalogTest {
  private val executor =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        )
    )

  private val wear =
    executor.listCatalogs().single {
      it.benchmark.catalogSystemId == CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
    }

  @Test
  fun `the wear catalog shelves its own components for the builder's menu`() {
    // The declaration replaces the base catalog's rather than merging with it. A merge would leave
    // every wear component unshelved — the m3 entries are keyed by `m3/…` ids this catalog does
    // not have — while carrying thirty-nine entries for components that are gone.
    val menu = wear.statusSemantics["componentMenu"]?.jsonObject
    val shelved = menu?.get("components")?.jsonObject?.keys.orEmpty()

    assertEquals(wear.components.map { it.componentId }.toSet(), shelved)
    assertEquals(
      listOf(
        "Screens",
        "Layout",
        "Lists",
        "Actions",
        "Selection",
        "Containment",
        "Communication",
        "Content",
        "Embedded",
      ),
      menu?.get("groupOrder")?.jsonArray?.map { it.jsonPrimitive.content },
    )
    // Every variant property it names is an enum on the component naming it — the same check
    // `CatalogMenuTest` runs against the M3 declaration, run here because this one is authored in
    // Kotlin beside a component list that #407 grew from six entries to twenty-eight.
    val components = wear.components.associateBy { it.componentId }
    menu?.get("components")?.jsonObject?.forEach { (componentId, entry) ->
      val property = entry.jsonObject["variantProperty"]?.jsonPrimitive?.content ?: return@forEach
      val declared =
        components.getValue(componentId).properties.singleOrNull { it.name == property }
      assertNotNull(declared, "$componentId declares no property $property")
      assertTrue(
        declared.allowedValues.isNotEmpty(),
        "$componentId.$property is not an enum, so it enumerates no variants",
      )
    }
    // And it still says the other thing it says about itself; one declaration did not evict the
    // other from the map they share.
    assertTrue("previewSurfaces" in wear.statusSemantics)
  }

  @Test
  fun `the wear catalog publishes the screen scaffold and the transforming lazy column`() {
    val ids = wear.components.map { it.componentId }

    assertTrue("wear-m3/screen-scaffold" in ids, ids.toString())
    assertTrue("wear-m3/transforming-lazy-column" in ids, ids.toString())
    assertEquals("wear-screen-scaffold-v1", wear.benchmark.catalogRevision)
  }

  /**
   * The scaffold carries the screen's furniture and NOT its size.
   *
   * The diameter is the document's frame — the Screen inspector's Wear OS presets — and a scaffold
   * property for it would be a second answer to the same question. This is the assertion that
   * notices if somebody adds one.
   */
  @Test
  fun `the screen scaffold takes no size property`() {
    val scaffold = wear.components.single { it.componentId == "wear-m3/screen-scaffold" }

    assertEquals(
      listOf("timeText", "scrollIndicator", "background"),
      scaffold.properties.map { it.name },
    )
    // `overlays` is the third and it is not a content slot: Wear's dialogs take a `visible` flag
    // and draw over the whole display, so the generator writes them as siblings of the
    // `ScreenScaffold` rather than as rows of its list. A slot in `content` would have made a
    // full-screen dialog into a scrolling item.
    assertEquals(listOf("content", "edgeButton", "overlays"), scaffold.slots.map { it.name })
    assertEquals("Scaffold", scaffold.role)
  }

  /**
   * Every component says, in its own capability note, that the canvas is not Wear Compose.
   *
   * `androidx.wear.compose:compose-material3` is an Android AAR and the canvas is Compose
   * Multiplatform for Wasm, so nothing in this catalog draws the real component. A note is how an
   * adapter states that rather than implying parity, and an unannotated component would be a claim
   * nobody made on purpose.
   */
  @Test
  fun `no component claims to draw the real Wear component`() {
    wear.components.forEach { component ->
      assertNotNull(component.wasm.notes, component.componentId)
      assertTrue(component.wasm.notes!!.isNotBlank(), component.componentId)
    }
  }

  /**
   * The rule this catalog exists under: **Material 3 and Wear Material 3 are not used together.**
   *
   * A Wear screen built from `m3/card` and `m3/text` claimed to hold mobile Material components,
   * and `WearScreenCodeExporter` then wrote them out as `TitleCard` and Wear's `Text` — the export
   * was right and the palette was lying. What may be borrowed from `m3-catalog` is foundation:
   * `Box`, `Column`, `Row` and `Image` are one declaration shared by both platforms, so borrowing
   * one claims nothing about Material at all.
   *
   * Asserted as a set rather than a predicate so that adding a borrow is a decision somebody writes
   * down here, not something that drifts in behind a convenient `m3/` id.
   */
  @Test
  fun `the wear catalog borrows foundation and no Material component`() {
    val borrowed = wear.components.map { it.componentId }.filterNot { it.startsWith("wear-m3/") }

    assertEquals(
      listOf(
        "layout/box",
        "layout/column",
        "layout/row",
        "asset/image",
        "remote-compose/document",
      ),
      borrowed,
    )
    assertTrue(borrowed.none { it.startsWith("m3/") }, borrowed.toString())
  }

  /** The content components a Wear screen is built from are Wear's own, named as Wear's. */
  @Test
  fun `the wear content ids are wear ids`() {
    val wearOwn = wear.components.map { it.componentId }.filter { it.startsWith("wear-m3/") }

    assertTrue("wear-m3/text" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/card" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/button" in wearOwn, wearOwn.toString())
    // Every one of them says what it draws and what it generates, which is what keeps a stand-in
    // from being mistaken for the real component.
    wearOwn.forEach { id ->
      val notes = wear.components.single { it.componentId == id }.wasm.notes.orEmpty()
      assertTrue(notes.isNotBlank(), id)
    }
  }

  /** An id with no packaged adapter is refused by name rather than served as something else. */
  @Test
  fun `a catalog with no adapter is refused`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("wear-m3-catalog"))
      }

    assertTrue("wear-m3-catalog" in failure.message.orEmpty(), failure.message.orEmpty())
    assertTrue("no packaged adapter" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  /**
   * Every Wear component the generator writes is a component the palette offers.
   *
   * The list is literal rather than derived, and it has to be: `:ui-builder-runtime` may not depend
   * on `:ui-builder-export` (`checkUiBuilderRuntimeBoundary` allows the protocol and nothing else),
   * so the two halves of this pairing live in modules that cannot see each other. What holds them
   * together is this set and `WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS` being edited in the
   * same change — and `WearComponentExportTest`, over in `:ui-builder`, asserting the other
   * direction: that every id in that set generates a call site.
   *
   * A component in one and not the other is the failure worth catching. In the palette and not the
   * generator is a drop that exports as a refusal; in the generator and not the palette is dead
   * code nobody can reach.
   */
  @Test
  fun `the palette offers every Wear component the generator can write`() {
    assertEquals(
      listOf(
        "wear-m3/alert-dialog",
        "wear-m3/button-group",
        "wear-m3/checkbox-button",
        "wear-m3/confirmation-dialog",
        "wear-m3/date-picker",
        "wear-m3/edge-button",
        "wear-m3/icon",
        "wear-m3/icon-button",
        "wear-m3/list-sub-header",
        "wear-m3/open-on-phone-dialog",
        "wear-m3/progress-indicator",
        "wear-m3/radio-button",
        "wear-m3/slider",
        "wear-m3/stepper",
        "wear-m3/switch-button",
        "wear-m3/text-button",
        "wear-m3/time-picker",
      ),
      wear.components
        .map { it.componentId }
        .filter { it.startsWith("wear-m3/") }
        .filterNot {
          it in
            setOf(
              "wear-m3/screen-scaffold",
              "wear-m3/transforming-lazy-column",
              "wear-m3/list-header",
              "wear-m3/text",
              "wear-m3/card",
              "wear-m3/button",
            )
        }
        .sorted(),
    )
  }

  /**
   * The catalog says which renderer may claim to be showing you a Wear design, and it is not this
   * browser.
   *
   * Read by `UiBuilderPreviewSurfaces`, which the editor uses to open a Wear design on the host's
   * renderer and to refuse the Preview mode where there is no host renderer, and by `:server` to
   * send the compile to the Robolectric daemon. All three of those used to be a guess made
   * separately — the editor's Preview mode showed a canvas of Material 3 lookalikes and called it a
   * preview, which is the specific false claim this declaration exists to retire.
   */
  @Test
  fun `the catalog declares its canvas approximate and its native render authoritative`() {
    val surfaces = wear.statusSemantics.getValue("previewSurfaces").jsonObject

    assertEquals(
      "approximate",
      surfaces.getValue("wasm").jsonObject.getValue("fidelity").jsonPrimitive.content,
    )
    assertTrue(
      surfaces.getValue("wasm").jsonObject.getValue("reason").jsonPrimitive.content.isNotBlank()
    )
    assertEquals(
      "authoritative",
      surfaces.getValue("native").jsonObject.getValue("fidelity").jsonPrimitive.content,
    )
    // Robolectric, and not a preference: `androidx.wear.compose:compose-material3` is an Android
    // AAR, so the desktop Skiko daemon cannot compile a Wear screen at all.
    assertEquals(
      "android",
      surfaces.getValue("native").jsonObject.getValue("backend").jsonPrimitive.content,
    )
  }
}
