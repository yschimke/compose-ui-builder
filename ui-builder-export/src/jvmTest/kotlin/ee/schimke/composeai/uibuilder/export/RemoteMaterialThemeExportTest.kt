package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** `remote-m3/remote-material-theme` re-skins its child with `RemoteMaterialTheme`. */
class RemoteMaterialThemeExportTest {
  private fun document(properties: String): UiBuilderDocument =
    Json.decodeFromJsonElement(
      Json.parseToJsonElement(
        """
        {"schema":"ui-builder-design-v1","id":"themed","title":"Themed","revision":1,
         "catalogPin":{},"environment":{},"stateVariables":{},
         "roots":["host"],
         "nodes":{
          "host":{"id":"host","componentId":"remote-m3/widget-container-large",
            "slots":{"content":["theme"]}},
          "theme":{"id":"theme","componentId":"remote-m3/remote-material-theme",
            "properties":$properties,"slots":{"children":["label"]}},
          "label":{"id":"label","componentId":"remote-m3/remote-text",
            "properties":{"text":{"type":"string","value":"Hi"},
              "color":{"type":"colorToken","value":"primary"}}}}}
        """
      ) as JsonObject
    )

  private fun exported(document: UiBuilderDocument): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(document, packageName = "proof.draw"),
        (WearWidgetCodeExporter.export(document) as? WearWidgetCodeExporter.Result.Refused)
          ?.reasons
          ?.toString(),
      )
      .source

  @Test
  fun `the overridden roles are a copy of the enclosing scheme around the child`() {
    val source =
      exported(
        document(
          """{"themePrimaryColor":{"type":"color","value":"#FFFF5722"},
            "themeOnPrimaryColor":{"type":"colorToken","value":"onSurface"}}"""
        )
      )
    File("build/draw-proof").apply { mkdirs() }.resolve("ThemeWidget.kt").writeText(source)

    assertContains(source, "import androidx.wear.compose.remote.material3.RemoteMaterialTheme")
    assertContains(source, "RemoteMaterialTheme(")
    assertContains(source, "colorScheme = RemoteMaterialTheme.colorScheme.copy(")
    assertContains(source, "primary = Color(0xFFFF5722).rc")
    assertContains(source, "onPrimary = RemoteMaterialTheme.colorScheme.onSurface")
    // The child is inside the theme's lambda and still reads the role, now overridden.
    val theme = source.indexOf("RemoteMaterialTheme(")
    val label = source.indexOf("\"Hi\"")
    kotlin.test.assertTrue(label > theme, source)
  }

  @Test
  fun `a theme overriding nothing writes the child alone`() {
    val source = exported(document("{}"))

    assertFalse("RemoteMaterialTheme(" in source, source)
    assertContains(source, "\"Hi\"")
  }
}
