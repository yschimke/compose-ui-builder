package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.SQUARE_WATCH_CORNER_FRACTION
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.WATCH_SCREEN_DP
import ee.schimke.composeai.uibuilder.editor.WatchScreenShape
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.editor.wearScreenPanes
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.weatherWidgetUiBuilderDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The opt-in "Wear panes": a library design on an approximate round and square watch, in the Small
 * and the Large Wear widget container.
 */
class WearScreenPaneTest {
  private val catalog = publishedTestCatalog("remote-m3")
  private val reducer = UiBuilderEditorReducer(catalog)
  private val reference =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** The weather widget's own container, gone: its content as a card from a library. */
  private val weather =
    weatherWidgetUiBuilderDocument("weather", reference.catalogPin, reference.environment)

  private val card: UiBuilderDocument =
    weather.copy(
      id = "weather-card",
      title = "Weather card",
      roots = listOf("weather-content"),
      nodes = weather.nodes - weather.roots.single(),
      environment =
        JsonObject(
          weather.environment +
            mapOf("widthDp" to JsonPrimitive(200), "heightDp" to JsonPrimitive(108))
        ),
    )

  private val captions =
    listOf("Round · Small", "Round · Large", "Square · Small", "Square · Large")

  @Test
  fun `a card gets a round and a square watch at both container sizes`() {
    val panes = card.wearScreenPanes()

    assertEquals(captions, panes.map { it.label })
    assertEquals(panes.size, panes.map { it.id }.toSet().size)
    val sizes =
      listOf(
        WearWidgetScaffoldSize.Small,
        WearWidgetScaffoldSize.Large,
        WearWidgetScaffoldSize.Small,
        WearWidgetScaffoldSize.Large,
      )
    panes.zip(sizes).forEach { (pane, size) ->
      val spec = size.hostSpec(WearWidgetHostShape.Squircle)
      // The pane is the watch; the container is the squircle host's, centred on it…
      assertEquals(WATCH_SCREEN_DP, pane.widthDp)
      assertEquals(WATCH_SCREEN_DP, pane.heightDp)
      assertEquals(spec, pane.wearHostFrame)
      assertEquals(WearWidgetHostShape.Squircle, pane.wearWidgetHostShape)
      val screen = checkNotNull(pane.watchScreen)
      assertEquals(spec.frameWidthDp.toFloat(), screen.frameWidthDp)
      assertEquals(spec.frameHeightDp.toFloat(), screen.frameHeightDp)
      assertEquals(26f, screen.frameCornerRadiusDp)
      // …and the design is laid out in the container's content box, as the catalog's Wear panes do.
      assertEquals(spec.contentWidthDp, pane.document.screenEnvironmentSettings().widthDp)
      assertEquals(spec.contentHeightDp, pane.document.screenEnvironmentSettings().heightDp)
    }
    assertEquals(
      listOf(
        WatchScreenShape.Round,
        WatchScreenShape.Round,
        WatchScreenShape.Square,
        WatchScreenShape.Square,
      ),
      panes.map { checkNotNull(it.watchScreen).shape },
    )
    // 200×60 and 200×108 content, inside 8dp of padding.
    assertEquals(216f to 76f, panes[0].watchScreen!!.let { it.frameWidthDp to it.frameHeightDp })
    assertEquals(216f to 124f, panes[1].watchScreen!!.let { it.frameWidthDp to it.frameHeightDp })
    assertEquals(0.2f, SQUARE_WATCH_CORNER_FRACTION)
  }

  @Test
  fun `a Wear widget brings its own container, at its own size`() {
    val panes = weather.wearScreenPanes()

    assertEquals(listOf("Round · Large", "Square · Large"), panes.map { it.label })
    panes.forEach { pane ->
      assertNull(pane.wearHostFrame)
      assertEquals(WearWidgetHostShape.Squircle, pane.wearWidgetHostShape)
      assertEquals(weather, pane.document)
    }
  }

  @Test
  fun `a Wear screen is already a watch, so it has no Wear panes`() {
    val screen = wearScreenUiBuilderDocument("screen", reference.catalogPin, JsonObject(emptyMap()))
    assertEquals(emptyList(), screen.wearScreenPanes())
  }

  @Test
  fun `the toggle is off by default, flips, and leaves the document alone`() {
    val initial = reducer.initial(card, selectedNodeId = null)
    assertFalse(initial.wearScreenPanes)

    val on = reducer.reduce(initial, UiBuilderEditorEvent.ToggleWearScreenPanes)
    val off = reducer.reduce(on, UiBuilderEditorEvent.ToggleWearScreenPanes)

    assertTrue(on.wearScreenPanes)
    assertFalse(off.wearScreenPanes)
    assertEquals(card, off.document)
  }

  /** Every edit and collaborator delta rebuilds the editor, so the choice must ride along. */
  @Test
  fun `the toggle survives a document arriving`() {
    val on =
      reducer.reduce(
        reducer.initial(card, selectedNodeId = null),
        UiBuilderEditorEvent.ToggleWearScreenPanes,
      )

    assertTrue(reducer.reconciled(on, card).wearScreenPanes)
    assertFalse(reducer.reconciled(on.copy(wearScreenPanes = false), card).wearScreenPanes)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `with the toggle off the preview draws no watches`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = card,
            catalog = catalog,
            initialPanes = setOf(EditorPane.Preview),
          )
        }
      }
      waitForIdle()
      captions.forEach { assertTrue(onAllNodesWithText(it).fetchSemanticsNodes().isEmpty(), it) }
      assertTrue(
        onAllNodesWithContentDescription("Watch screen", substring = true)
          .fetchSemanticsNodes()
          .isEmpty()
      )
    }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `with the toggle on the preview draws a card on four watches`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = card,
            catalog = catalog,
            initialPanes = setOf(EditorPane.Preview),
            initialWearScreenPanes = true,
          )
        }
      }
      waitForIdle()
      captions.forEach { onNodeWithText(it).assertExists() }
      assertEquals(
        4,
        onAllNodesWithContentDescription("Watch screen", substring = true)
          .fetchSemanticsNodes()
          .size,
      )
      capture("card")
    }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `a Wear widget joins its host panes on the watches`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = weather,
            catalog = catalog,
            initialPanes = setOf(EditorPane.Preview),
            initialWearScreenPanes = true,
          )
        }
      }
      waitForIdle()
      // Its own host panes stay…
      onNodeWithText("Pixel Watch").assertExists()
      // …and the watches follow.
      onNodeWithText("Round · Large").assertExists()
      onNodeWithText("Square · Large").assertExists()
      capture("widget")
    }

  /** The "Also compare" chip is the switch, and what it says is what a host persists. */
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `the Also compare chip switches the watches on`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = card,
            catalog = catalog,
            initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
            initialInspectorMode = EditorInspectorMode.Screen,
            initialInspectorOpen = true,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()
      assertTrue(onAllNodesWithText("Round · Small").fetchSemanticsNodes().isEmpty())

      onNodeWithContentDescription("Compare Wear panes").performClick()
      waitForIdle()

      runOnIdle { assertTrue(checkNotNull(latest).wearScreenPanes) }
      captions.forEach { onNodeWithText(it).assertExists() }
    }

  /** Written only when `WEAR_PANES_EVIDENCE` names a directory; see `docs/evidence/wear-panes`. */
  @OptIn(ExperimentalTestApi::class)
  private fun ComposeUiTest.capture(name: String) {
    val dir = System.getenv("WEAR_PANES_EVIDENCE")?.takeIf { it.isNotBlank() } ?: return
    val screenshot = onAllNodes(isRoot()).onFirst().captureToImage()
    File(dir, "$name.png").apply {
      parentFile.mkdirs()
      writeBytes(
        checkNotNull(
            Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
          )
          .bytes
      )
    }
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
