package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The remote-m3 palette offers each progress indicator's `progress`: the record's determinate call
 * takes it, and the policy's `indeterminate` property is added to the record's, not put in place of
 * them. A design that could not set it drew a ring by hand instead.
 */
class RemoteProgressIndicatorCatalogTest {
  private val catalog = publishedTestCatalog("remote-m3")

  @Test
  fun `every remote progress indicator offers progress`() {
    listOf(
        "remote-m3/remote-circular-progress-indicator",
        "remote-m3/remote-linear-progress-indicator",
        "remote-m3/remote-curved-progress-indicator",
      )
      .forEach { id ->
        val component = assertNotNull(catalog.componentsById[id], "$id is not on the palette")
        val names = component.properties.map { it.name }
        assertTrue("progress" in names, "$id offers $names")
      }
  }

  @Test
  fun `the circular indicator keeps its record's angles beside the stated indeterminate flag`() {
    val names =
      catalog.componentsById
        .getValue("remote-m3/remote-circular-progress-indicator")
        .properties
        .map { it.name }

    assertTrue("indeterminate" in names, names.toString())
    assertTrue("startAngle" in names && "endAngle" in names, names.toString())
  }
}
