package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The remote-m3 palette offers each progress indicator's `progress`, from the record's determinate
 * call. A design that could not set it drew a ring by hand instead.
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
  fun `the circular indicator keeps its record's angles`() {
    val names =
      catalog.componentsById
        .getValue("remote-m3/remote-circular-progress-indicator")
        .properties
        .map { it.name }

    assertTrue("startAngle" in names && "endAngle" in names, names.toString())
  }
}
