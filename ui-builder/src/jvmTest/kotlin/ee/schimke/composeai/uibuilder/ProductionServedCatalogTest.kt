package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A design whose catalog this renderer doesn't package (e.g. `glimmer-catalog`), drawn in a
 * production PNG export: the host passes its served catalog, so components draw as named
 * placeholders rather than "Unsupported component".
 */
@OptIn(ExperimentalTestApi::class)
class ProductionServedCatalogTest {
  /** A served catalog: any capability document holding the component drawn. */
  private val served = SyntheticCatalogs.A2UI_JSON

  /** Pinned to a catalog this renderer packages nothing for, using one of the served components. */
  private val document = decodeProductionRendererDocument(document("unpackaged-catalog"))

  @Test
  fun `the served catalog is used only when the renderer packages none for the pin`() {
    val catalog = assertNotNull(productionCatalogFor(document, served))
    assertTrue("a2ui/Divider" in catalog.componentsById)
    assertNull(productionCatalogFor(document, ""))
    assertNull(productionCatalogFor(document, "not a catalog"), "an unreadable catalog is ignored")
    // A packaged pin keeps its packaged catalog whatever the host sends.
    val packaged = decodeProductionRendererDocument(document("a2ui-catalog"))
    assertEquals(
      productionPreviewCatalog(packaged)?.componentsById?.keys,
      productionCatalogFor(packaged, served)?.componentsById?.keys,
    )
  }

  @Test
  fun `with no catalog the export draws the error box`() =
    runDesktopComposeUiTest(width = 400, height = 300) {
      setContent { ProductionUiBuilderSurface(document) }
      onNodeWithText("Unsupported component", substring = true).assertExists()
    }

  @Test
  fun `with the served catalog a component is the catalog's, not unsupported`() =
    runDesktopComposeUiTest(width = 400, height = 300) {
      setContent { ProductionUiBuilderSurface(document, servedCatalog = served) }
      // Fails before the fix: "Unsupported component: a2ui/Divider".
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  private fun document(systemId: String) =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"served-catalog",
     "title":"Served catalog","revision":1,
     "catalogPin":{"systemId":"$systemId","catalogRevision":"candidate",
       "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
     "environment":{"widthDp":200,"heightDp":150,"density":1.0,"theme":"dark",
       "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
     "stateVariables":{},"roots":["divider"],
     "nodes":{
      "divider":{"id":"divider","componentId":"a2ui/Divider","properties":{},
        "modifiers":[],"slots":{},"eventBindings":{}}}}
    """
      .trimIndent()
}
