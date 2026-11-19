package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.NewDesignLink
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignScreen
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The New design form kept in the address bar, and the editor's one-click "make another": a link
 * reopens the form as it was, a bare home URL stays bare until someone picks, and "New mobile app"
 * creates without the form.
 */
@OptIn(ExperimentalTestApi::class)
class NewDesignLinkScreenTest {
  private val catalogs =
    listOf(
      UiBuilderNewDesignCatalog(
        systemId = "m3-catalog",
        label = "Mobile app",
        primary = true,
        templates =
          listOf(
            UiBuilderNewDesignTemplate("blank", "Blank", ""),
            UiBuilderNewDesignTemplate("jetcaster-discover", "Discover", ""),
          ),
      ),
      UiBuilderNewDesignCatalog(
        systemId = "wff-watchface",
        label = "Watch face",
        primary = true,
        platform = UiBuilderCatalogPlatform.WATCH_FACE,
        templates =
          listOf(
            UiBuilderNewDesignTemplate("analog-watch-face", "Analog Watch Face", ""),
            UiBuilderNewDesignTemplate("digital-watch-face", "Digital Watch Face", ""),
          ),
      ),
    )

  @Test
  fun `a link opens the form at its choices, and creating uses them`() = runComposeUiTest {
    val created = mutableListOf<String>()
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        initialDesignId = "sunny-otter",
        initialLink =
          NewDesignLink(device = "watch", kind = "watchface", template = "digital-watch-face"),
        onCreate = { catalog, _, template, _, _ -> created += "$catalog/$template" },
      )
    }
    onNodeWithText("Digital Watch Face").performScrollTo().assertIsSelected()
    onNodeWithText("Create").performScrollTo().performClick()
    assertEquals(listOf("wff-watchface/digital-watch-face"), created)
  }

  @Test
  fun `a bare home page writes nothing until a choice changes, then every choice`() =
    runComposeUiTest {
      val written = mutableListOf<NewDesignLink>()
      setContent {
        UiBuilderNewDesignScreen(
          catalogs = catalogs,
          initialCatalogSystemId = "m3-catalog",
          initialDesignId = "sunny-otter",
          onLinkChange = { written += it },
          onCreate = { _, _, _, _, _ -> },
        )
      }
      waitForIdle()
      assertTrue(written.isEmpty())

      onNodeWithText("Discover").performScrollTo().performClick()
      waitForIdle()
      assertEquals(
        NewDesignLink(device = "phone", kind = "m3-catalog", template = "jetcaster-discover"),
        written.last(),
      )

      // A typed title is shared; the generated one never was.
      onNodeWithContentDescription("Design title").performTextReplacement("Checkout")
      waitForIdle()
      assertEquals("Checkout", written.last().title)

      // Back to the defaults is written too, so the address never shows a stale choice.
      onNodeWithContentDescription("Design title").performTextReplacement("")
      onNodeWithText("Blank").performScrollTo().performClick()
      waitForIdle()
      assertEquals(
        NewDesignLink(device = "phone", kind = "m3-catalog", template = "blank"),
        written.last(),
      )
    }

  @Test
  fun `the overflow menu makes another design like this one in one click`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText(),
        )
      val document =
        UiBuilderReducer.replay(
            Json.parseToJsonElement(
                checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json"))
                  .readText(),
              )
              .jsonObject,
          )
          .document
      val created = mutableListOf<List<String>>()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            newDesignCatalogs = catalogs,
            newDesignTemplateFor = { if (it == "m3-catalog") "jetcaster-discover" else null },
            onCreateDesign = { catalogId, designId, template, state, title ->
              created += listOf(catalogId, template, state.size.toString())
              assertTrue(designId.isNotBlank())
              assertTrue(title.isNotBlank())
            },
          )
        }
      }
      waitForIdle()
      onNodeWithContentDescription("More editor actions").performClick()
      waitForIdle()
      onNodeWithText("New mobile app").performClick()
      waitForIdle()
      assertEquals(listOf(listOf("m3-catalog", "jetcaster-discover", "0")), created)
      // No form on the way.
      onNodeWithText("Create a new design").assertDoesNotExist()
    }
}
