package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Creating a Wear screen from the New design chooser: `wear-m3` must offer templates through its
 * published templates.
 */
class WearScreenNewDesignTest {
  private val fixture =
    Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject

  private fun seed(templateId: String) =
    publishedTestSeed(
      systemId = "wear-m3",
      templateId = templateId,
      designId = "watch",
      catalogRevision = "wear-screen-scaffold-v1",
      nativeRuntimeId = "candidate",
    )

  @Test
  fun `the wear catalog offers the empty screen and the worked list`() {
    assertEquals(
      setOf("wear-screen", "wear-list"),
      publishedTestTemplates("wear-m3").ids.toSet(),
    )
  }

  /**
   * The empty template already holds the list: `ScreenScaffold` exists to hold a
   * `TransformingLazyColumn`, and the generator refuses any other content.
   */
  @Test
  fun `the empty screen opens on a scaffold over an empty list`() {
    val document = seed("wear-screen")

    assertEquals(listOf("wear-screen"), document.roots)
    val scaffold = document.nodes.getValue("wear-screen")
    assertEquals("wear-m3/screen-scaffold", scaffold.componentId)
    assertEquals(listOf("wear-list"), scaffold.slots.getValue("content"))
    assertEquals("10:10", scaffold.property("timeText"))
    assertEquals("true", scaffold.property("scrollIndicator"))
    val list = document.nodes.getValue("wear-list")
    assertEquals("wear-m3/transforming-lazy-column", list.componentId)
    assertTrue(list.slots.getValue("items").isEmpty())
  }

  /**
   * Seeded on a watch frame, since the scaffold reads its diameter from the document (a handset
   * frame would disagree with the Screen inspector).
   */
  @Test
  fun `a wear design is created on the small round frame`() {
    val environment = seed("wear-screen").environment

    assertEquals("192", environment.getValue("widthDp").jsonPrimitive.content)
    assertEquals("192", environment.getValue("heightDp").jsonPrimitive.content)
    assertEquals("2.0", environment.getValue("density").jsonPrimitive.content)
    assertEquals("dark", environment.getValue("theme").jsonPrimitive.content)
  }

  /** More than a screenful: six 64dp rows and a header is 496dp against a 192dp display. */
  @Test
  fun `the worked list is longer than one screen`() {
    val document = seed("wear-list")

    val items = document.nodes.getValue("wear-list").slots.getValue("items")
    assertEquals(7, items.size)
    assertEquals("list-header", items.first())
    assertEquals((0..5).map { "row-$it" }, items.drop(1))
  }

  /**
   * The generated screen carries both previews: `@WearPreviewDevices` (one transformed screenful
   * per round size) and the `ScrollMode.LONG` capture (the whole extent untransformed, matching the
   * canvas, for parity checks).
   */
  @Test
  fun `the generated screen carries the single-screen preview and the parity capture`() {
    val source = assertEmitted(WearScreenCodeExporter.export(seed("wear-list")))

    assertTrue("@WearPreviewDevices" in source, source)
    assertTrue("fun ActivityScreenPreview() {\n    AppScaffold(" in source, source)
    assertTrue(
      "@Preview(device = \"id:wearos_small_round\", showBackground = true, backgroundColor = 0xFF000000)" in
        source,
      source,
    )
    assertTrue("@ScrollingPreview(modes = [ScrollMode.LONG])" in source, source)
    assertTrue("fun ActivityScreenLongPreview() {\n    AppScaffold(" in source, source)
    assertTrue("import ee.schimke.composeai.preview.ScrollMode" in source, source)
    assertTrue("import ee.schimke.composeai.preview.ScrollingPreview" in source, source)
  }

  /** An empty list still generates: a screen with no rows yet is a screen, not a refusal. */
  @Test
  fun `the empty screen generates a compiling shell`() {
    val source = assertEmitted(WearScreenCodeExporter.export(seed("wear-screen")))

    assertTrue("TransformingLazyColumn(" in source, source)
    assertTrue("fun UntitledWearScreen()" in source, source)
  }

  private fun assertEmitted(result: WearScreenCodeExporter.Result): String =
    when (result) {
      is WearScreenCodeExporter.Result.Emitted -> result.source
      is WearScreenCodeExporter.Result.Refused -> error(result.reasons.joinToString("\n"))
    }

  private fun UiBuilderNode.property(name: String): String =
    properties[name]?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
