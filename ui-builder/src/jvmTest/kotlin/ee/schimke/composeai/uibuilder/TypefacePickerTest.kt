package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.EditorInspectorMode
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.ResetTypefaceEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetTypefaceEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.UpdateEnvironmentMutationV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The design's typeface: the vendored families, the registry that loads them, and the picker. */
@OptIn(ExperimentalTestApi::class)
class TypefacePickerTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /** The same files the browser builds copy into `fonts/`. */
  private val fontsDir = File("../assets/rc-fonts")

  private fun diskRegistry(scope: CoroutineScope) =
    UiBuilderFontRegistry(
      scope = scope,
      readManifest = { File(fontsDir, "fonts.json").readText() },
      readFont = { File(fontsDir, it).readBytes() },
    )

  @Test
  fun `the manifest lists every vendored family, with generic names made readable`() {
    val manifest = parseVendoredFontManifest(File(fontsDir, "fonts.json").readText())
    val names = manifest.families.map { it.name }
    assertTrue("Space Grotesk" in names, "families: $names")
    assertTrue("Roboto Flex" in names, "families: $names")
    assertEquals("Serif", manifest.families.single { it.name == "serif" }.label)
    assertEquals("Space Grotesk", manifest.families.single { it.name == "Space Grotesk" }.label)
    // Every file the manifest names ships beside it, or the family could never load.
    manifest.families
      .flatMap { it.fonts }
      .forEach { font -> assertTrue(File(fontsDir, font.file).isFile, "missing ${font.file}") }
  }

  /** First load stays free of font traffic: a design without a typeface never fetches anything. */
  @Test
  fun `nothing is fetched until a font is asked for`() = runBlocking {
    var manifestReads = 0
    var fontReads = 0
    val registry =
      UiBuilderFontRegistry(
        scope = this,
        readManifest = {
          manifestReads++
          File(fontsDir, "fonts.json").readText()
        },
        readFont = {
          fontReads++
          File(fontsDir, it).readBytes()
        },
      )
    repeat(5) { yield() }
    assertEquals(0, manifestReads)
    assertEquals(0, fontReads)

    registry.request("Orbitron")
    withTimeout(10_000) { while ("Orbitron" !in registry.loaded) yield() }
    assertEquals(1, manifestReads)
    assertEquals(2, fontReads, "Orbitron ships a 400 and a 700")
  }

  @Test
  fun `a family loads when it is asked for, and not before`() = runBlocking {
    val registry = diskRegistry(this)
    registry.request("Space Grotesk")
    withTimeout(10_000) { while ("Space Grotesk" !in registry.loaded) yield() }
    assertEquals(setOf("Space Grotesk"), registry.loaded.keys)
    // A name the host does not ship is ignored rather than failed: the renderer draws it in the
    // default face.
    registry.request("Comic Sans")
    yield()
    assertFalse("Comic Sans" in registry.loaded)
  }

  @Test
  fun `picking a typeface writes it, and choosing Default resets it`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    assertNull(initial.document.screenEnvironmentSettings().typeface)

    val picked =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(typeface = "Space Grotesk")
        ),
      )
    assertEquals("Space Grotesk", picked.document.screenEnvironmentSettings().typeface)
    val set = wireChange(reducer.acceptedSubmission(initial, picked))
    assertEquals(SetTypefaceEnvironmentChangeV1("Space Grotesk"), set)

    val cleared =
      reducer.reduce(
        picked,
        UiBuilderEditorEvent.UpdateEnvironment(
          picked.document.screenEnvironmentSettings().copy(typeface = null)
        ),
      )
    // Cleared means absent: the document reads as one that never chose a family.
    assertFalse("typeface" in cleared.document.environment)
    assertIs<ResetTypefaceEnvironmentChangeV1>(
      wireChange(reducer.acceptedSubmission(picked, cleared))
    )
  }

  /**
   * The clear is recorded as absent, not as JSON null: undo checks the field still holds what the
   * command wrote, and an absent field never equals a recorded null — so undoing Default was
   * refused, and redoing it would have written an explicit null back.
   */
  @Test
  fun `choosing Default can be undone and redone`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    fun typeface(state: UiBuilderEditorState) = state.document.screenEnvironmentSettings().typeface
    val picked =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(typeface = "Inter")
        ),
      )
    val cleared =
      reducer.reduce(
        picked,
        UiBuilderEditorEvent.UpdateEnvironment(
          picked.document.screenEnvironmentSettings().copy(typeface = null)
        ),
      )
    assertNull(typeface(cleared))

    val undone = reducer.reduce(cleared, UiBuilderEditorEvent.Undo)
    assertEquals("Inter", typeface(undone), "undo of Default was refused")

    val redone = reducer.reduce(undone, UiBuilderEditorEvent.Redo)
    assertNull(typeface(redone))
    assertFalse(
      "typeface" in redone.document.environment,
      "redo wrote ${redone.document.environment}",
    )
  }

  @Test
  fun `an unrelated frame edit does not touch the typeface`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val next =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(fontScale = 1.3)
        ),
      )
    assertFalse("typeface" in next.document.environment)
  }

  @Test
  fun `the picker lists the vendored families and commits the one picked`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var latest: UiBuilderEditorState? = null
      var registry: UiBuilderFontRegistry? = null
      setContent {
        val scope = rememberCoroutineScope()
        val fonts = remember { diskRegistry(scope).also { registry = it } }
        MaterialTheme {
          ProvideUiBuilderFonts(fonts) {
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
      }
      waitUntil(timeoutMillis = 10_000) { assertNotNull(registry).families.isNotEmpty() }

      onNodeWithContentDescription("Typeface").performScrollTo().performClick()
      // Opening the menu asks for every family, so each option can be drawn in its own face.
      waitUntil(timeoutMillis = 10_000) { "Space Grotesk" in assertNotNull(registry).loaded }
      onNodeWithText("Space Grotesk").performClick()
      waitForIdle()
      runOnIdle {
        assertEquals(
          "Space Grotesk",
          assertNotNull(latest).document.screenEnvironmentSettings().typeface,
        )
      }

      onNodeWithContentDescription("Typeface").performScrollTo().performClick()
      onNodeWithContentDescription("Current typeface").assertExists()
      onAllNodesWithText("Default").onFirst().performClick()
      waitForIdle()
      runOnIdle { assertNull(assertNotNull(latest).document.screenEnvironmentSettings().typeface) }
    }

  @Test
  fun `the canvas redraws in the design's family once it has loaded`() =
    runDesktopComposeUiTest(width = 800, height = 900) {
      var registry: UiBuilderFontRegistry? = null
      val designed =
        document.copy(
          environment =
            JsonObject(document.environment + ("typeface" to JsonPrimitive("Lobster Two")))
        )
      var fontsOn by mutableStateOf(false)
      setContent {
        val scope = rememberCoroutineScope()
        val fonts = remember { diskRegistry(scope).also { registry = it } }
        // No registry for the first picture, so it is the default face whatever the dispatcher
        // does; then the same document with the registry, which must load and use the family.
        MaterialTheme {
          ProvideUiBuilderFonts(if (fontsOn) fonts else null) {
            UiBuilderSurface(document = designed)
          }
        }
      }
      waitForIdle()
      val before = onRoot().captureToImage().toPixelMap()
      fontsOn = true
      waitUntil(timeoutMillis = 10_000) { "Lobster Two" in assertNotNull(registry).loaded }
      waitForIdle()
      val after = onRoot().captureToImage().toPixelMap()
      var differing = 0
      for (x in 0 until before.width) for (y in 0 until before.height) {
        if (before[x, y] != after[x, y]) differing++
      }
      assertTrue(differing > 500, "only $differing pixels changed when the family loaded")
    }

  private fun wireChange(submission: EditorSubmission?) =
    assertIs<UpdateEnvironmentMutationV1>(
        assertIs<DesignCommandV1>(
            assertIs<EditorSubmission.Batch>(submission)
              .toProtocolSubmission("actor", "client", document.revision)
          )
          .operations
          .single()
      )
      .changes
      .single()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
