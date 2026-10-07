package ee.schimke.composeai.uibuilder.renderer.sdk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalog keeps only icon keys and derives each icon's label and `Icons` expression from its
 * key. This holds the derivation, and the catalog's order, to the inventory the build reads from
 * material-icons-extended.
 */
class MaterialIconCatalogTest {
  private val inventory: List<GoogleMaterialIcon> =
    File(
        checkNotNull(System.getProperty("materialIconInventory")) { "materialIconInventory unset" }
      )
      .readLines()
      .filterNot { it.startsWith("#") || it.isBlank() }
      .map { line ->
        val (key, label, expression, _, canonical) = line.split('\t')
        GoogleMaterialIcon(key, label, expression, canonical.toBooleanStrict())
      }

  @Test
  fun everyIconIsDerivedExactlyAsTheInventoryHasIt() {
    assertEquals(11_431, inventory.size)
    inventory.forEach { expected -> assertEquals(expected, googleMaterialIcon(expected.key)) }
  }

  @Test
  fun theCatalogFollowsItsOrder() {
    val ordered =
      inventory.sortedWith(
        compareByDescending(GoogleMaterialIcon::canonical)
          .thenBy(GoogleMaterialIcon::label)
          .thenBy(GoogleMaterialIcon::key)
      )
    assertEquals(ordered, GoogleMaterialIcons)
    ordered.forEachIndexed { position, icon ->
      assertEquals(position, GoogleMaterialIconKeys.positionOf(icon.key))
    }
  }

  @Test
  fun keysOutsideTheCatalogAreUnknown() {
    listOf(
        "",
        "filled",
        "filled/",
        "filled/notAnIcon",
        "bold/home",
        "autoMirrored/home",
        "home|filled/add",
      )
      .forEach { assertNull(googleMaterialIcon(it), it) }
    assertEquals(-1, GoogleMaterialIconKeys.positionOf("filled/notAnIcon"))
  }

  @Test
  fun searchFindsWhatTheLabelFilterFoundWithoutTheCatalog() {
    assertEquals(SelectableGoogleMaterialIcons.size, SelectableGoogleMaterialIconCount)
    assertEquals(SelectableGoogleMaterialIcons.take(80), searchGoogleMaterialIcons("", limit = 80))
    listOf(
        "arrow",
        "Arrow back",
        "outlined",
        "Auto-mirrored",
        "10k",
        "TwoTone",
        "HOME",
        "zzzz",
        "Auto-mirrored Outlined",
        "Arrow Back — Outlined",
        "d Rot",
      )
      .forEach { query ->
        val byLabel = SelectableGoogleMaterialIcons.filter {
          it.label.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)
        }
        val found = searchGoogleMaterialIcons(query, limit = Int.MAX_VALUE)
        assertEquals(byLabel, found.filter { it in byLabel }, "order for '$query'")
        assertTrue(found.containsAll(byLabel), "'$query' missed ${byLabel - found.toSet()}")
        assertEquals(found.take(80), searchGoogleMaterialIcons(query, limit = 80))
      }
    assertEquals(emptyList(), searchGoogleMaterialIcons("zzzz", limit = 80))
  }
}
