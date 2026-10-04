package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.editor.UiBuilderHomeDesign
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignScreen
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.UiBuilderReleaseNote
import ee.schimke.composeai.uibuilder.editor.homeDesignFolders
import ee.schimke.composeai.uibuilder.export.NEW_DESIGN_ID
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The builder's home page: the screen `/ui-builder/` draws when no design is named.
 *
 * It used to be the New design dialog on an empty background, so the only thing a person could do
 * from the product's front door was make something new. What is pinned here is that the two other
 * beginnings are now reachable from it — open one that exists, or start a new design *from* one —
 * and that the panel offering them is absent rather than empty where the host cannot do either.
 */
@OptIn(ExperimentalTestApi::class)
class UiBuilderHomeScreenTest {
  private val catalogs =
    listOf(
      UiBuilderNewDesignCatalog(
        systemId = "m3-catalog",
        label = "Mobile",
        templates = listOf(UiBuilderNewDesignTemplate("blank", "Blank", "An empty scaffold")),
      )
    )

  private val designs =
    listOf(
      UiBuilderHomeDesign(
        designId = "morning-player",
        title = "Morning player",
        catalogSystemId = "m3-catalog",
        updatedLabel = "updated yesterday",
      )
    )

  @Test
  fun `the home screen lists what is already here and offers both beginnings`() = runComposeUiTest {
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        designs = designs,
        onOpenDesign = {},
        onCopyDesign = {},
        onBrowseDesigns = {},
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithText("New design").assertIsDisplayed()
    onNodeWithText("Mobile app").assertIsDisplayed()
    onNodeWithText("Available kinds").assertDoesNotExist()
    onNodeWithText("Catalog").assertDoesNotExist()
    onNodeWithText("Adaptive app").assertDoesNotExist()
    onNodeWithText("List-detail screen").assertDoesNotExist()
    onNodeWithText("Recent designs").assertIsDisplayed()
    onNodeWithText("Morning player").assertIsDisplayed()
    onNodeWithText("morning-player · m3-catalog · updated yesterday").assertIsDisplayed()
    onNodeWithContentDescription("All designs").assertIsDisplayed()
  }

  /**
   * A preview hands the form a fixed name so its render is the same every time; the app passes
   * nothing and a person gets a generated one.
   */
  @Test
  fun `a given design name is pre-filled, and the app's default is a generated one`() =
    runComposeUiTest {
      var initial by mutableStateOf<String?>("sunny-otter")
      var createdId: String? = null
      setContent {
        key(initial) {
          UiBuilderNewDesignScreen(
            catalogs = catalogs,
            initialCatalogSystemId = "m3-catalog",
            initialDesignId = initial,
            onCreate = { _, designId, _, _ -> createdId = designId },
          )
        }
      }
      onNodeWithContentDescription("Design ID").assertTextContains("sunny-otter")
      onNodeWithText("Create").performScrollTo().performClick()
      assertEquals("sunny-otter", createdId)

      initial = null
      waitForIdle()
      onNodeWithText("Create").performScrollTo().performClick()
      val generated = assertNotNull(createdId)
      assertNotEquals("sunny-otter", generated)
      assertTrue(generated.isNotBlank())
    }

  @Test
  fun `opening and copying name the design they were pressed on`() = runComposeUiTest {
    var opened: String? = null
    var copied: String? = null
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        designs = designs,
        onOpenDesign = { opened = it },
        onCopyDesign = { copied = it },
        onBrowseDesigns = {},
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithContentDescription("Open morning-player").performClick()
    assertEquals("morning-player", opened)
    assertNull(copied)

    onNodeWithContentDescription("Start from morning-player").performClick()
    assertEquals("morning-player", copied)
  }

  @Test
  fun `a design starts unfiled and can be moved into a new folder`() = runComposeUiTest {
    var moved: Pair<String, String?>? = null
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        designs = designs,
        onMoveDesign = { designId, folder -> moved = designId to folder },
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithText("Folder ·").assertDoesNotExist()
    onNodeWithContentDescription("Move morning-player").performClick()
    onNodeWithContentDescription("New folder").performTextInput("Music")
    onNodeWithText("Create folder and move").performClick()
    assertEquals("morning-player" to "Music", moved)
  }

  @Test
  fun `filed designs are listed under their folder, unfiled last`() = runComposeUiTest {
    val filed =
      listOf(
        UiBuilderHomeDesign("loose-sketch", "Loose sketch", "m3-catalog"),
        UiBuilderHomeDesign("watch-face", "Watch face", "m3-catalog", folder = "wear"),
        UiBuilderHomeDesign("tile-draft", "Tile draft", "m3-catalog", folder = "Tiles"),
      )
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        designs = filed,
        onOpenDesign = {},
        onCreate = { _, _, _, _ -> },
      )
    }

    val headings =
      onAllNodes(isHeading()).fetchSemanticsNodes().map {
        it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
      }
    assertEquals(listOf("Tiles", "wear", "No folder"), headings.filter { it in FOLDER_HEADINGS })
    // The heading says the folder, so the row no longer repeats it.
    onNodeWithText("Folder · wear").assertDoesNotExist()
    assertEquals(
      listOf("tile-draft", "watch-face", "loose-sketch"),
      homeDesignFolders(filed).flatMap { (_, group) -> group.map { it.designId } },
    )
  }

  @Test
  fun `nothing filed stays one list with no folder headings`() = runComposeUiTest {
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        designs = designs,
        onOpenDesign = {},
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithText("No folder").assertDoesNotExist()
  }

  /**
   * Creating still works, and still says what it was asked for.
   *
   * The form is shared with the dialog the editor opens now, so this is the assertion that the
   * extraction did not quietly change what **Create** submits.
   */
  @Test
  fun `creating reports the catalog, the template and the generated id`() = runComposeUiTest {
    var created: List<String>? = null
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        onCreate = { catalog, designId, template, _ ->
          created = listOf(catalog, designId, template)
        },
      )
    }

    onNodeWithContentDescription("Create design").performScrollTo().performClick()
    val submitted = checkNotNull(created)
    assertEquals("m3-catalog", submitted[0])
    assertEquals("blank", submitted[2])
    // Whatever was rolled, it is a valid id — which is what makes the button pressable at all.
    assertEquals(true, NEW_DESIGN_ID.matches(submitted[1]), submitted[1])
  }

  /** A host with no index and nothing to copy shows the create panel alone, as it always did. */
  @Test
  fun `the chosen starting point is submitted and release notes remain available`() =
    runComposeUiTest {
      val submitted = mutableListOf<String>()
      setContent {
        UiBuilderNewDesignScreen(
          catalogs =
            listOf(
              UiBuilderNewDesignCatalog(
                "m3-catalog",
                "Android app",
                listOf(
                  UiBuilderNewDesignTemplate("blank", "Blank screen", ""),
                  UiBuilderNewDesignTemplate("hello", "Hello sample", ""),
                ),
              )
            ),
          initialCatalogSystemId = "m3-catalog",
          releaseNotes = listOf(UiBuilderReleaseNote("9.9.0", "2026-09-25", listOf("a new thing"))),
          onCreate = { catalog, _, template, _ -> submitted += "$catalog/$template" },
        )
      }
      onNodeWithText("What's new").performScrollTo().assertIsDisplayed()
      onNodeWithText("• A new thing").performScrollTo().assertIsDisplayed()
      onNodeWithText("Hello sample").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      onNodeWithText("Blank screen").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals(listOf("m3-catalog/hello", "m3-catalog/blank"), submitted)
    }

  @Test
  fun `switching design types shows only their templates and resets the selection`() =
    runDesktopComposeUiTest(width = 400, height = 900) {
      var created: Pair<String, String>? = null
      val types =
        listOf(
          UiBuilderNewDesignCatalog(
            "m3-catalog",
            "Android app",
            listOf(
              UiBuilderNewDesignTemplate("blank", "Blank screen", "Empty phone screen"),
              UiBuilderNewDesignTemplate("hello", "Hello sample", "Phone starter"),
            ),
          ),
          UiBuilderNewDesignCatalog(
            "wear-m3",
            "Wear",
            listOf(
              UiBuilderNewDesignTemplate("wear-screen", "Blank screen", "Empty watch screen"),
              UiBuilderNewDesignTemplate("wear-list", "Activity list", "Watch starter"),
            ),
          ),
          UiBuilderNewDesignCatalog(
            "remote-m3",
            "RemoteCompose",
            listOf(
              UiBuilderNewDesignTemplate("wear-widget-small", "Blank small widget", "Empty widget"),
              UiBuilderNewDesignTemplate("weather-widget", "Weather", "Widget starter"),
            ),
          ),
          UiBuilderNewDesignCatalog(
            "a2ui",
            "A2UI surface",
            listOf(UiBuilderNewDesignTemplate("column", "Column", "A2UI starter")),
          ),
        )
      setContent {
        UiBuilderNewDesignScreen(
          catalogs = types,
          initialDesignId = "sunny-otter",
          initialCatalogSystemId = "m3-catalog",
          onCreate = { catalog, _, template, _ -> created = catalog to template },
        )
      }
      fun capture(name: String) {
        val folder =
          File(System.getProperty("uiBuilderProjectDir"), "build/new-design-evidence").apply {
            mkdirs()
          }
        File(folder, "$name.png")
          .writeBytes(
            requireNotNull(
                Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
                  .encodeToData(EncodedImageFormat.PNG)
              )
              .bytes
          )
      }
      capture("mobile-app")
      onNodeWithText("Hello sample").performClick()
      onNodeWithText("Wear app").performClick()
      onNodeWithText("Hello sample").assertDoesNotExist()
      onNodeWithText("Activity list").assertIsDisplayed()
      capture("wear-app")
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals("wear-m3" to "wear-screen", created)
      onNodeWithText("Activity list").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals("wear-m3" to "wear-list", created)
      onNodeWithText("Wear widget").performScrollTo().performClick()
      onNodeWithText("Activity list").assertDoesNotExist()
      capture("wear-widget")
      onNodeWithText("Weather").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals("remote-m3" to "weather-widget", created)
      onNodeWithText("Mobile app").performScrollTo().performClick()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals("m3-catalog" to "blank", created)
      onNodeWithText("A2UI surface").assertDoesNotExist()
      onNodeWithText("Other design types…").performScrollTo().performClick()
      onNodeWithText("A2UI surface").performClick()
      onNodeWithText("Column").assertIsDisplayed()
      onNodeWithContentDescription("Create design").performScrollTo().performClick()
      assertEquals("a2ui" to "column", created)
    }

  @Test
  fun `the designs panel is absent where the host offers neither`() = runComposeUiTest {
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithText("New design").assertIsDisplayed()
    onNodeWithText("Recent designs").assertDoesNotExist()
  }
}

private val FOLDER_HEADINGS = setOf("Tiles", "wear", "No folder")
