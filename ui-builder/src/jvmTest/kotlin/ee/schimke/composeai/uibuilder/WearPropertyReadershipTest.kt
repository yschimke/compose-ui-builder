package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every property the Wear catalog declares is read by something that draws or writes it.
 *
 * `wear-m3` is synthesised (`wearOwn` copies and renames mobile components), so it can inherit
 * properties (e.g. `containerColor`, `shape`, `elevationDp` on a card) that neither the canvas nor
 * the exporter read. This crude check requires each declared name to appear in one of the three
 * sources that draw or emit Wear components.
 *
 * A name read for another component still passes; parsing each dispatch branch isn't worth it.
 * Exemptions fail in both directions, so a stale entry is flagged once the drawing arrives (as
 * `StickerBakeCoverageTest.knownBlank` and `RemoteRenderTest.knownDuplicate` do).
 * `UI_BUILDER_CATALOG_AUDIT.md` records the clickable-node model `onClickAction` waits on.
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
      listOf(
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/WearScreenCodeExporter.kt",
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/WearContentEmitter.kt",
          // The screen theme's roles, which the canvas and the generator both read through it.
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/WearScreenTheme.kt",
          // And its typefaces, read the same way.
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/ThemeTypefaces.kt",
          // And a text's axes and features, named there for every lane.
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/FontSettings.kt",
          // And a variable font text's axes, which both lanes read by these names.
          "src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/VariableFontText.kt",
        )
        .map { moduleFile("ui-builder-export", it) }

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
    /** Properties declared and deliberately not drawn, each with the reason. Empty is the goal. */
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
