package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The upstream Wear sample designs through the Wear screen generator, written out for reading.
 *
 * `DesignFixturesTest` already requires every fixture to export; this is the Wear samples' own
 * check, and the one that leaves the Kotlin somewhere a person can open it
 * (`build/wear-sample-designs/`). What the output does and does not say against the Wear Material 3
 * guidance is written up in `docs/design/UI_BUILDER_WEAR_SAMPLES.md`; the assertions here are the
 * parts of that write-up that are fixed and must stay fixed.
 */
class WearSampleDesignExportTest {
  private val samples =
    mapOf(
      "wear-starter-greeting" to "StarterGreetingScreen",
      "wear-starter-list" to "StarterListScreen",
      "jetcaster-wear-library" to "JetcasterLibraryScreen",
      "jetcaster-wear-episode" to "JetcasterEpisodeScreen",
      "jetcaster-wear-queue" to "JetcasterQueueScreen",
    )

  /** An outlined button draws no container, so it has none to recolour. */
  @Test
  fun `a container colour on an outlined button is refused`() {
    val file = File(designsDirectory(), "wear-starter-list.json")
    val document =
      UiBuilderReducer.replay(Json.parseToJsonElement(file.readText()).jsonObject).document
    val settings = document.nodes.getValue("settings")
    val outlined =
      document.copy(
        nodes =
          document.nodes +
            ("settings" to
              settings.copy(
                properties =
                  JsonObject(
                    settings.properties +
                      ("variant" to
                        Json.parseToJsonElement("{\"type\":\"enum\",\"value\":\"outlined\"}"))
                  )
              ))
      )
    val result = RecordFreeExport.generate(outlined, UiBuilderCatalogPlatform.WEAR)
    assertTrue(result is RecordFreeExport.Generated.Refused, "$result")
    assertTrue(result.reasons.any { "draws no container" in it }, "${result.reasons}")
  }

  @Test
  fun `every upstream Wear sample exports as a Wear screen`() {
    val out = File("build/wear-sample-designs").apply { mkdirs() }
    samples.forEach { (design, screen) ->
      val source = export(design)
      File(out, "$screen.kt").writeText(source)
      assertTrue("fun $screen()" in source, "$design declares no $screen:\n$source")
      assertTrue("ScreenScaffold(scrollState = listState" in source, source)
      assertTrue("TransformingLazyColumn(" in source, source)
    }
  }

  /** The ComposeStarter greeting is two lines, and the literal it generates must stay on one. */
  @Test
  fun `the two-line greeting is an escaped literal`() {
    val source = export("wear-starter-greeting")
    assertTrue("Text(text = \"From the Round world,\\nHello, Android!\")" in source, source)
    assertTrue("EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.ExtraSmall)" in source, source)
  }

  /**
   * The findings of `UI_BUILDER_WEAR_SAMPLES.md`, each pinned against the sample that showed it.
   * Every one is what the upstream sample itself writes.
   */
  @Test
  fun `the samples generate what the upstream screens call`() {
    val list = export("wear-starter-list")
    // The app root's, not the screen's: the previews supply AppScaffold and the frozen time.
    val screen = list.substringAfter("fun StarterListScreen() {").substringBefore("\n}\n")
    assertFalse("AppScaffold" in screen, screen)
    assertTrue(
      "AppScaffold(timeText = { TimeText { timeTextCurvedText(\"10:10\") } }) { StarterListScreen() }" in
        list,
      list,
    )
    // Authored modifiers reach the code, before the list's own treatment and padding.
    assertTrue(
      "modifier = Modifier.fillMaxWidth().transformedHeight(this, spec)" +
        ".minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding)," in list,
      list,
    )
    // A card's second line is its body, not a subtitle.
    assertFalse("subtitle =" in list, list)
    assertTrue("title = {\n                        Text(text = \"Example Title\")" in list, list)
    // Every row keeps its component's minimum list padding.
    assertTrue("ListHeaderDefaults.minimumTopListContentPadding" in list, list)
    assertTrue("ButtonGroupDefaults.minimumVerticalListContentPadding" in list, list)
    // ButtonGroup scales and fades with the rows around it.
    assertTrue(
      "ButtonGroupDefaults.minimumVerticalListContentPadding),\n" +
        "                    transformation = SurfaceTransformation(spec)," in list,
      list,
    )
    // An icon that is the whole button is named for a screen reader.
    assertTrue("contentDescription = \"Settings Button\"," in list, list)
    // Hoisted state reads as Kotlin.
    assertTrue("var errorDialogVisible by remember { mutableStateOf(false) }" in list, list)
    // The dialog's buttons are Wear's own, with their icons and content descriptions.
    assertTrue(
      "confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { errorDialogVisible = false }) }," in
        list,
      list,
    )
    assertTrue(
      "dismissButton = { AlertDialogDefaults.DismissButton(onClick = { errorDialogVisible = false }) }," in
        list,
      list,
    )
    assertTrue("import androidx.wear.compose.material3.AlertDialogDefaults" in list, list)
    // Hierarchy by colour, through the variant's own colours function.
    assertTrue(
      "colors = IconButtonDefaults.filledIconButtonColors(" +
        "containerColor = MaterialTheme.colorScheme.secondary, " +
        "contentColor = MaterialTheme.colorScheme.onSecondary)," in list,
      list,
    )
    val library = export("jetcaster-wear-library")
    assertTrue(
      "colors = ButtonDefaults.filledTonalButtonColors(" +
        "containerColor = MaterialTheme.colorScheme.surfaceContainer)," in library,
      library,
    )

    val episode = export("jetcaster-wear-episode")
    assertTrue("modifier = Modifier.weight(0.7f)," in episode, episode)
    assertTrue("modifier = Modifier.weight(0.3f)," in episode, episode)
    assertTrue("style = MaterialTheme.typography.bodySmall," in episode, episode)
    assertTrue("overflow = TextOverflow.Ellipsis," in episode, episode)
    assertTrue("TextDefaults.minimumTopListContentPadding" in episode, episode)

    // Buttons use Wear's slots: an icon, a label, and a secondary label.
    val queue = export("jetcaster-wear-queue")
    assertTrue("icon = {" in queue, queue)
    assertTrue("secondaryLabel = {" in queue, queue)
    assertFalse("Column {" in queue, queue)
    assertTrue("contentDescription = \"Delete queue\"," in queue, queue)
  }

  private fun export(design: String): String {
    val file = File(designsDirectory(), "$design.json")
    val document =
      UiBuilderReducer.replay(Json.parseToJsonElement(file.readText()).jsonObject).document
    return when (val result = RecordFreeExport.generate(document, UiBuilderCatalogPlatform.WEAR)) {
      is RecordFreeExport.Generated.Emitted -> result.source
      is RecordFreeExport.Generated.Refused -> fail("$design was refused: ${result.reasons}")
      null -> fail("$design did not route to a record-free generator")
    }
  }

  private fun designsDirectory(): File =
    File(
      System.getProperty("uiBuilderDesignFixturesDir")
        ?: "../docs/design/fixtures/ui-builder/designs"
    )
}
