package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Creating a Wear screen from the New design chooser, which is the front door the canvas needs.
 *
 * Everything downstream of this was already true — the scaffold drew, the list drew, the generator
 * emitted — and none of it was reachable: `wear-m3` offered no templates, so
 * `UiBuilderNewDesignSeed.templateIds` refused every id and there was no way to make a Wear design
 * except by hand-writing a document.
 */
class WearScreenNewDesignTest {
  private val fixture =
    Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject

  private fun seed(templateId: String) =
    UiBuilderNewDesignSeed.document(
      designId = "watch",
      catalogSystemId = "wear-m3",
      templateId = templateId,
      catalogRevision = "wear-screen-scaffold-v1",
      nativeRuntimeId = "candidate",
      fixture = fixture,
    )

  @Test
  fun `the wear catalog offers the empty screen and the worked list`() {
    assertEquals(
      setOf("wear-screen", "wear-list"),
      UiBuilderNewDesignSeed.templateIds("wear-m3"),
    )
  }

  /**
   * The list is in the empty template, not something to add afterwards.
   *
   * `ScreenScaffold` exists to hold a `TransformingLazyColumn`; its `contentPadding` means nothing
   * until something reads it, and the generator refuses a scaffold whose content slot holds
   * anything else. Opening on the pair is opening on the shape of the thing.
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
   * The watch's own frame, because the scaffold reads its diameter from the document.
   *
   * Seeded on the fixture's handset, a Wear design would fall back to the smallest watch to draw
   * itself while the Screen inspector said "Pixel" — a disagreement between the picture and the
   * frame menu that nobody could act on.
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
   * The generated screen carries both previews, and they answer different questions.
   *
   * `@WearPreviewDevices` is the screen as a watch shows it — one screenful, transformed, at every
   * round size. The `ScrollMode.LONG` capture is the whole extent with the transformation off,
   * which is the picture the builder's canvas draws, so it is the one a parity check compares.
   */
  @Test
  fun `the generated screen carries the single-screen preview and the parity capture`() {
    val source = assertEmitted(WearScreenCodeExporter.export(seed("wear-list")))

    assertTrue("@WearPreviewDevices" in source, source)
    assertTrue("fun ActivityScreenPreview() = ActivityScreen()" in source, source)
    assertTrue(
      "@Preview(device = \"id:wearos_small_round\", showBackground = true, backgroundColor = 0xFF000000)" in
        source,
      source,
    )
    assertTrue("@ScrollingPreview(modes = [ScrollMode.LONG])" in source, source)
    assertTrue("fun ActivityScreenLongPreview() = ActivityScreen()" in source, source)
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
