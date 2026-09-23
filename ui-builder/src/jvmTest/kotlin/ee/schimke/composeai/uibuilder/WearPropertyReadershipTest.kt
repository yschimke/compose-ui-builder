package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every property the Wear catalog declares is read by something that draws or writes it.
 *
 * ## The drift this exists to stop
 *
 * `wear-m3` is a *synthesised* catalog: `wearOwn` copies a mobile component and renames it, so a
 * Wear card inherited `containerColor`, `shape`, `elevationDp`, `stableKey` and `onClickAction`
 * from `m3/card` without anybody asking whether Wear's `TitleCard` takes them. It takes none of the
 * first three, and the canvas and the exporter read none of them either — so an author could move a
 * control that moved nothing, on both lanes, and only a person comparing the catalog against the
 * renderer would notice. The same was true of the screen template's `shape = "large"`, whose
 * comment explained which theme radius it reached when it reached nothing at all.
 *
 * Nothing failed, because nothing compared a *declaration* against the code that consumes it. This
 * does, in the crudest way that still catches the case: a declared property name has to appear
 * somewhere in the three sources that draw or emit a Wear component.
 *
 * ## What it deliberately does not catch
 *
 * A name read *for another component* passes. `maxLines` appears in the renderer for `m3/text`, so
 * a Wear component declaring it and ignoring it would slip through — that gap is why the check is
 * paired with [the exemptions below] rather than trusted on its own. Closing it properly means
 * parsing each dispatch branch, which is a larger machine than this drift justifies; what is worth
 * catching is the property *nothing* reads, and that is what it catches.
 *
 * ## Why the exemptions fail in both directions
 *
 * An entry here is a claim that a property is deliberately not drawn. When the drawing arrives the
 * entry is stale, and the same failure that would have caught the original gap is the one that
 * announces the fix — the pattern `StickerBakeCoverageTest.knownBlank` and
 * `RemoteRenderTest.knownDuplicate` already use. `UI_BUILDER_CATALOG_AUDIT.md` records the
 * clickable-node model that `onClickAction` waits on.
 */
class WearPropertyReadershipTest {

  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/wear-m3-capabilities-v1.json")) {
          "missing the wear capability golden on the test resources path"
        }
        .readText()
    )

  /** The sources that draw a Wear component on the canvas or write one into generated Kotlin. */
  private val laneSources =
    listOf(
        "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/UiBuilderRenderer.kt",
        "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/WearCanvasComponents.kt",
      )
      .map { moduleFile("ui-builder", it) } +
      moduleFile(
        "ui-builder-export",
        "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/WearScreenCodeExporter.kt",
      )

  @Test
  fun `every declared Wear property is read somewhere in the Wear lane`() {
    val unread = declaredProperties().filterNot { (_, name) -> read(name) }
    val unreadNames = unread.map { (component, name) -> "$component.$name" }.sorted()

    assertEquals(
      EXEMPTIONS.keys.sorted(),
      unreadNames,
      "a declared property nothing reads is a control an author can move that moves nothing. " +
        "Either read it where it belongs, drop it from the declaration, or add it here with a " +
        "reason — and delete the entry the day it is read, because this list is checked in both " +
        "directions.",
    )
  }

  @Test
  fun `an exemption that has been read is deleted`() {
    val closed =
      EXEMPTIONS.keys
        .filter { key -> declaredProperties().any { (c, n) -> "$c.$n" == key } }
        .filter { key -> read(key.substringAfterLast('.')) }
    assertTrue(
      closed.isEmpty(),
      "$closed are read now, so the exemption is stale — the property is drawn and the note " +
        "saying it is not is the same silent lie in the other direction.",
    )
  }

  private fun read(name: String): Boolean = laneSources.any {
    it.isFile && "\"$name\"" in it.readText()
  }

  /** `componentId` to property name, for every component the Wear catalog publishes. */
  private fun declaredProperties(): List<Pair<String, String>> =
    catalog.components
      .filter { it.componentId.startsWith("wear-m3/") }
      .flatMap { component ->
        component.properties.map { property -> component.componentId to property.name }
      }

  private fun moduleFile(module: String, path: String): File =
    File("$module/$path").let { if (it.isFile) it else File("../$module/$path") }

  private companion object {
    /**
     * Properties that are declared and deliberately not drawn, each with the reason. Empty is the
     * goal: an entry is a known gap, not a licence.
     */
    val EXEMPTIONS: Map<String, String> =
      mapOf(
        "wear-m3/button.onClickAction" to
          "Inherited from `m3/button`, where it is also unread: nothing in this repository turns a " +
            "click into an action yet, and UI_BUILDER_CATALOG_AUDIT.md records the clickable-node " +
            "model as the unfinished feature that would. Deleting the property would be a " +
            "statement about the mobile catalog too, which this repository does not own.",
        "wear-m3/card.onClickAction" to
          "The same property as `wear-m3/button`'s, from `m3/card`, and the same unfinished " +
            "clickable-node model.",
      )
  }
}
