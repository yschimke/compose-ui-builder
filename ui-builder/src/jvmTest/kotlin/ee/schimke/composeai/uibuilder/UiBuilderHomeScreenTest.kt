package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    onNodeWithText("Start something new").assertIsDisplayed()
    onNodeWithText("Your designs").assertIsDisplayed()
    onNodeWithText("Morning player").assertIsDisplayed()
    onNodeWithText("morning-player · m3-catalog · updated yesterday").assertIsDisplayed()
    onNodeWithContentDescription("All designs").assertIsDisplayed()
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

    onNodeWithContentDescription("Create design").performClick()
    val submitted = checkNotNull(created)
    assertEquals("m3-catalog", submitted[0])
    assertEquals("blank", submitted[2])
    // Whatever was rolled, it is a valid id — which is what makes the button pressable at all.
    assertEquals(true, NEW_DESIGN_ID.matches(submitted[1]), submitted[1])
  }

  /** A host with no index and nothing to copy shows the create panel alone, as it always did. */
  @Test
  fun `the designs panel is absent where the host offers neither`() = runComposeUiTest {
    setContent {
      UiBuilderNewDesignScreen(
        catalogs = catalogs,
        initialCatalogSystemId = "m3-catalog",
        onCreate = { _, _, _, _ -> },
      )
    }

    onNodeWithText("Start something new").assertIsDisplayed()
    onNodeWithText("Your designs").assertDoesNotExist()
  }
}
