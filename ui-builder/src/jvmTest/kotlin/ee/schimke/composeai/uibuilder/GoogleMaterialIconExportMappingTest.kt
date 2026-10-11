package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.renderer.sdk.GoogleMaterialIcon
import ee.schimke.composeai.uibuilder.renderer.sdk.GoogleMaterialIcons
import ee.schimke.composeai.uibuilder.renderer.sdk.SelectableGoogleMaterialIcons
import ee.schimke.composeai.uibuilder.renderer.sdk.googleMaterialIcon
import ee.schimke.composeai.uibuilder.renderer.sdk.imageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The canvas and the export must name the same icon for a key. The root generator emits both
 * modules' views from the shipped core + extended artifacts; [GoogleMaterialIcons] holds real
 * `ImageVector`s, while `:ui-builder-export` has no Compose dependency (server classpath).
 *
 * Checks both sides agree which member a key names (a key missing from one table, `genres`
 * resolving differently, an `Icons.AutoMirrored` move on one side). Member existence is checked by
 * the compiler.
 */
class GoogleMaterialIconExportMappingTest {

  @Test
  fun `the export table names the same member as the palette, for every key`() {
    assertEquals(
      GoogleMaterialIcons.associate { it.key to it.composeExpression.removePrefix("Icons.") },
      ScreenDocumentProjection.ICON_MEMBERS,
      "the builder palette and the Compose export disagree about an icon key",
    )
  }

  @Test
  fun `every advertised key resolves to the generated ImageVector member`() {
    GoogleMaterialIcons.forEach { icon ->
      val member = icon.composeExpression.substringAfterLast('.')
      assertTrue(
        icon.imageVector.name.endsWith(member),
        "${icon.key} resolved to ${icon.imageVector.name}, expected ${icon.composeExpression}",
      )
    }
  }

  @Test
  fun `the generated inventory exposes every style and the common editor icons`() {
    assertEquals(11_385, SelectableGoogleMaterialIcons.size)
    assertEquals(11_431, GoogleMaterialIcons.size)
    val keys = GoogleMaterialIcons.mapTo(mutableSetOf(), GoogleMaterialIcon::key)
    listOf("chat", "comment", "palette", "devices", "error", "history", "code", "tune", "widgets")
      .forEach { name ->
        listOf("filled", "outlined", "rounded", "twoTone", "sharp").forEach { style ->
          assertTrue("$style/$name" in keys, "$style/$name is missing")
        }
      }
    assertTrue("autoMirrored/filled/chat" in keys)
    assertNotNull(googleMaterialIcon("filled/chat")?.imageVector)
    assertTrue(googleMaterialIcon("filled/chat")?.label?.contains("Chat") == true)
  }

  @Test
  fun `legacy keys remain aliases and never duplicate picker rows`() {
    assertEquals("Icons.Filled.Category", googleMaterialIcon("genres")?.composeExpression)
    assertEquals(
      "Icons.AutoMirrored.Filled.ArrowBack",
      googleMaterialIcon("arrowBack")?.composeExpression,
    )
    assertFalse(SelectableGoogleMaterialIcons.any { !it.canonical })
    assertFalse(SelectableGoogleMaterialIcons.any { it.key == "genres" })
  }
}
