package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorLayoutDirection
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorScreenTheme
import ee.schimke.composeai.uibuilder.editor.EditorVariantAxis
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Desktop pointer coverage for the two non-drag interactions a visual editor must not hide behind a
 * rail: choosing a layer starts editing it, while its secondary click opens its context menu.
 *
 * Palette and canvas drags are covered by [BesideDropTest] and [CanvasMoveDragTest]. This owns the
 * native mouse path specifically: touch input cannot prove a secondary button reaches the menu.
 */
@OptIn(ExperimentalTestApi::class)
class EditorDesktopPointerInteractionTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(fixture).document

  @Test
  fun `a primary layer click opens the selected component properties`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      editor()

      onNodeWithContentDescription("Select root-surface").performMouseInput { click() }

      onNodeWithText("Properties").assertExists()
      onNodeWithText("m3/surface").assertExists()
    }

  @Test
  fun `a secondary layer click selects it and opens its action menu`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      editor()

      onNodeWithContentDescription("Select root-surface").performMouseInput { rightClick() }

      onNodeWithText("Duplicate").assertExists()
      onNodeWithText("Delete").assertExists()
    }

  @Test
  fun `toolbar history reverses and restores an inspector edit`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()
      val originalText =
        assertNotNull(latest).document.nodes.getValue("main-episode-title").properties

      onNodeWithContentDescription("Text property").performTextReplacement("History episode")
      onNodeWithContentDescription("Apply text").performClick()
      waitForIdle()
      runOnIdle {
        assertTrue(
          assertNotNull(latest).document.nodes.getValue("main-episode-title").properties !=
            originalText
        )
      }

      onNodeWithContentDescription("Undo (Ctrl/⌘+Z)").performClick()
      waitForIdle()
      runOnIdle {
        assertEquals(
          originalText,
          assertNotNull(latest).document.nodes.getValue("main-episode-title").properties,
        )
      }

      onNodeWithContentDescription("Redo (Ctrl/⌘+Shift+Z)").performClick()
      waitForIdle()
      runOnIdle {
        assertTrue(
          assertNotNull(latest).document.nodes.getValue("main-episode-title").properties !=
            originalText
        )
      }
    }

  @Test
  fun `host rendered rail opens and closes the component browser`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Open components panel").performClick()
      onNodeWithContentDescription("Component catalog search").assertExists()

      onNodeWithContentDescription("Close components panel").performClick()
      onNodeWithContentDescription("Component catalog search").assertDoesNotExist()
    }

  @Test
  fun `a secondary canvas click opens actions for the component under the pointer`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            initialSelectedNodeId = "main-episode-title",
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      val cover =
        onAllNodesWithContentDescription("Android Developers Backstage cover")
          .fetchSemanticsNodes()
          .maxBy { it.boundsInRoot.left }
          .boundsInRoot
      onRoot().performMouseInput { rightClick(Offset(cover.center.x, cover.center.y)) }

      onNodeWithText("Duplicate").assertExists()
      onNodeWithText("Delete").assertExists()
    }

  @Test
  fun `an M3 text property applies through the desktop inspector`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Text property").performTextReplacement("Edited episode")
      onNodeWithContentDescription("Apply text").performClick()

      assertTrue(
        onAllNodesWithText("Edited episode").fetchSemanticsNodes().isNotEmpty(),
        "the updated text is rendered on the canvas",
      )
    }

  @Test
  fun `keyboard commits single and multiline properties`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Text property").performTextReplacement("Keyboard episode")
      onNodeWithContentDescription("Text property").performKeyInput {
        keyDown(Key.CtrlLeft)
        pressKey(Key.Enter)
        keyUp(Key.CtrlLeft)
      }
      onNodeWithContentDescription("Property search").performTextReplacement("color")
      onNodeWithContentDescription("Add Color property").performClick()
      onNodeWithContentDescription("Color property").performTextReplacement("#ff336699")
      onNodeWithContentDescription("Color property").performKeyInput { pressKey(Key.Enter) }

      assertTrue(onAllNodesWithText("Keyboard episode").fetchSemanticsNodes().isNotEmpty())
      onNodeWithContentDescription("Color property").assertTextEquals("#ff336699")

      onNodeWithContentDescription("Color property").performTextReplacement("not-a-color")
      onNodeWithContentDescription("Color property").performKeyInput { pressKey(Key.Enter) }
      onNodeWithText("Edit was not applied · value retained").assertExists()
      onNodeWithContentDescription("Color property").assertTextEquals("not-a-color")
    }

  @Test
  fun `host rendered boolean property commits its checked state`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      val softWrap =
        reducer.propertyFields(reducer.initial(document, "main-episode-title")).single {
          it.name == "softWrap"
        }
      val initial = softWrap.value.toBoolean()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Property search").performTextReplacement("soft wrap")
      if (!softWrap.written) {
        onNodeWithContentDescription("Add ${softWrap.label} property").performClick()
      }
      onNodeWithContentDescription("${softWrap.label} property").performClick()
      waitForIdle()

      runOnIdle {
        val committed =
          reducer
            .propertyFields(assertNotNull(latest))
            .single { it.name == "softWrap" }
            .value
            .toBoolean()
        assertEquals(!initial, committed)
      }
    }

  @Test
  fun `host rendered theme fields apply as one edit`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialInspectorMode = EditorInspectorMode.Theme,
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Primary colour").performTextReplacement("#ff123456")
      onNodeWithContentDescription("Apply theme").performClick()
      waitForIdle()

      runOnIdle {
        assertEquals("#ff123456", reducer.themeSettings(assertNotNull(latest)).primaryColor)
      }
    }

  @Test
  fun `host rendered screen fields and choices apply together`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialInspectorMode = EditorInspectorMode.Screen,
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Width (dp)").performTextReplacement("777")
      onNodeWithContentDescription("Dark theme").performClick()
      onNodeWithContentDescription("Right to left layout direction").performClick()
      onNodeWithContentDescription("Apply screen settings").performClick()
      waitForIdle()

      runOnIdle {
        val settings = assertNotNull(latest).document.screenEnvironmentSettings()
        assertEquals(777, settings.widthDp)
        assertEquals(EditorScreenTheme.Dark, settings.theme)
        assertEquals(EditorLayoutDirection.Rtl, settings.layoutDirection)
      }
    }

  @Test
  fun `host can keep the visual editor in a view without an embedded preview`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialPanes = setOf(EditorPane.Editor),
            availablePanes = setOf(EditorPane.Editor),
            openDefaultPreview = false,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      runOnIdle { assertEquals(setOf(EditorPane.Editor), assertNotNull(latest).panes) }
      onNodeWithContentDescription("Workspace panes (Editor)").assertDoesNotExist()
    }

  @Test
  fun `host can keep preview output in a chrome-free view`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialPanes = setOf(EditorPane.Preview),
            availablePanes = setOf(EditorPane.Preview, EditorPane.Native),
            openDefaultPreview = false,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      runOnIdle { assertEquals(setOf(EditorPane.Preview), assertNotNull(latest).panes) }
      onNodeWithContentDescription("Workspace panes (Preview)").assertDoesNotExist()
      onNodeWithText("UI Builder").assertDoesNotExist()
    }

  @Test
  fun `host rendered screen pickers update the frame exports and comparison strip`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      val phone = UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
      val tablet =
        UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            devicePresets = listOf(phone, tablet),
            initialInspectorMode = EditorInspectorMode.Screen,
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Device preset").performClick()
      onNodeWithContentDescription("Pixel Tablet").performClick()
      onNodeWithContentDescription("Compare Dark").performClick()
      onNodeWithContentDescription("Export devices").performClick()
      onNodeWithContentDescription("Pixel 7").performClick()
      waitForIdle()

      runOnIdle {
        val state = assertNotNull(latest)
        val settings = state.document.screenEnvironmentSettings()
        assertEquals(1280, settings.widthDp)
        assertEquals(800, settings.heightDp)
        assertEquals(listOf(phone.id), settings.exportDevices)
        assertTrue(EditorVariantAxis.Dark in state.variantAxes)
      }
    }

  @Test
  fun `host rendered binding controls unbind and rebind a property`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "chip-crime",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Unbind selectedCategory").performClick()
      waitForIdle()

      runOnIdle {
        assertEquals(
          null,
          reducer
            .propertyFields(assertNotNull(latest))
            .single { it.name == "selected" }
            .boundVariable,
        )
      }

      onNodeWithContentDescription("Bind Selected to state").performClick()
      onNodeWithText("selectedCategory").performClick()
      onNodeWithContentDescription("Selected state comparison").performTextReplacement("Crime")
      onNodeWithText("Bind").performClick()
      waitForIdle()

      runOnIdle {
        assertEquals(
          "selectedCategory",
          reducer
            .propertyFields(assertNotNull(latest))
            .single { it.name == "selected" }
            .boundVariable,
        )
      }
    }

  @Test
  fun `host rendered enum control commits a menu choice`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Style property").performClick()
      onNodeWithText("bodyLarge").performClick()
      waitForIdle()

      runOnIdle {
        assertEquals(
          "bodyLarge",
          reducer.propertyFields(assertNotNull(latest)).single { it.name == "style" }.value,
        )
      }
    }

  @Test
  fun `selection changes retain an uncommitted property draft`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialLayersOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Text property").performTextReplacement("Retained draft")
      onNodeWithContentDescription("Select root-surface").performClick()
      onNodeWithText("1 uncommitted edit retained").assertExists()
      onAllNodesWithText("Episode 140: Lorem ipsum dolor")[0].performClick()

      onNodeWithContentDescription("Text property").assertTextEquals("Retained draft")
      onNodeWithText("Uncommitted edit retained · Ctrl/⌘+Enter applies").assertExists()
    }

  @Test
  fun `submitting the authored value does not retain a hidden draft`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithText("Use sample text").performClick()
      waitForIdle()
      onNodeWithText("Use sample text").performClick()

      onNodeWithText("1 uncommitted edit retained").assertDoesNotExist()
    }

  @Test
  fun `deleting a node discards its retained draft`() =
    runDesktopComposeUiTest(width = 800, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialLayersOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Text property").performTextReplacement("Discard this draft")
      onNodeWithContentDescription("More editor actions").performClick()
      onNodeWithText("Delete").performClick()

      onNodeWithText("1 uncommitted edit retained").assertDoesNotExist()
    }

  private fun androidx.compose.ui.test.ComposeUiTest.editor() {
    setContent {
      MaterialTheme {
        UiBuilderEditor(
          document = document,
          catalog = catalog,
          initialLayersOpen = true,
          initialCanvasZoom = 1f,
        )
      }
    }
    waitForIdle()
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    private val fixture =
      Json.parseToJsonElement(
          checkNotNull(
              EditorDesktopPointerInteractionTest::class
                .java
                .getResource("/jetcaster-discover-operations-v1.json")
            )
            .readText()
        )
        .jsonObject
  }
}
