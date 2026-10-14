package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A published Wear row reaches the same record-backed emitter in the editor as on the server. */
class WearServedRecordExportTest {
  @Test
  fun `published Wear record aliases and catalog record rows agree both ways`() {
    val catalog = publishedTestCatalog("wear-m3")
    val record = publishedTestExportRecord("wear-m3")
    val catalogIds = catalog.componentsById.keys
    // ScreenScaffold is a builder-owned screen root. The Wear record supplies content rows.
    val declared =
      catalog.statusSemantics
        .getValue("components")
        .jsonObject
        .filterValues { it.jsonObject["record"]?.jsonPrimitive?.content != null }
        .keys
        .filterTo(mutableSetOf()) {
          it.startsWith("wear-m3/") && it in catalogIds && it != "wear-m3/screen-scaffold"
        }
    val served =
      record.components
        .flatMapTo(mutableSetOf()) { it.componentIds }
        .filterTo(mutableSetOf()) { it.startsWith("wear-m3/") && it in catalogIds }

    assertEquals(declared, served)
  }

  @Test
  fun `published app card generates from the served record`() {
    val catalog = publishedTestCatalog("wear-m3")
    val record = publishedTestExportRecord("wear-m3")
    val id = "wear-m3/app-card"
    assertTrue(catalog.componentsById.containsKey(id), "published menu lost $id")
    assertTrue(record.components.any { id in it.componentIds }, "served record lost $id")

    val base =
      wearScreenUiBuilderDocument("activity", JsonObject(emptyMap()), JsonObject(emptyMap()))
    val list = base.nodes.getValue("wear-list")
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-list" to list.copy(slots = mapOf("items" to listOf("card")))) +
            ("card" to UiBuilderNode(id = "card", componentId = id))
      )

    val generated = UiBuilderEditorReducer(catalog, catalogRecord = record).generatedCode(document)
    val source = assertIs<EditorGeneratedCode.Source>(generated, "$generated").kotlin
    assertTrue("AppCard(" in source, source)

    val missingRecord = UiBuilderEditorReducer(catalog).generatedCode(document)
    val refused = assertIs<EditorGeneratedCode.Refused>(missingRecord, "$missingRecord")
    assertTrue(refused.reasons.any { id in it }, refused.reasons.toString())
  }
}
