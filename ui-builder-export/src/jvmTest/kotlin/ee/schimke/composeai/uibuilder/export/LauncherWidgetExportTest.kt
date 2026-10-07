package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A `remote-widgets` launcher widget design → a `RemoteComposeWidget`.
 *
 * The design is the `counter-widget` template the catalog publishes (yschimke/remote-m3-catalog,
 * `widget-catalog/ui-builder/designs/counter-widget.json`), which reproduces AndroidX's `MyWidget`
 * demo. What is asserted here is the shape — a `RemoteComposeWidget`, creation-compose text, a
 * preview at the design's launcher grid size and nothing from Wear.
 */
class LauncherWidgetExportTest {

  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun counter(): UiBuilderDocument =
    json.decodeFromString(UiBuilderDocument.serializer(), COUNTER_WIDGET)

  private fun export(document: UiBuilderDocument = counter()): RecordFreeExport.Generated? =
    RecordFreeExport.generate(
      document,
      UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      packageName = "ee.schimke.remotewidgets.generated",
      packComponents = records,
    )

  @Test
  fun `the widget is a RemoteComposeWidget and names no Wear library`() {
    val source = (export() as RecordFreeExport.Generated.Emitted).source
    assertContains(source, "class CounterWidget : RemoteComposeWidget() {")
    assertContains(source, "override fun Content(context: Context, widgetId: Int) {")
    assertContains(source, "import androidx.compose.remote.creation.compose.layout.RemoteText")
    assertContains(source, "RemoteContentPreview(profile = RcPlatformProfiles.WIDGETS_V6)")
    assertFalse("androidx.wear" in source, source)
    assertFalse("androidx.glance" in source, source)
    assertFalse("RemoteMaterialTheme" in source, source)
  }

  @Test
  fun `the preview is framed at the design's launcher grid size`() {
    val source = (export() as RecordFreeExport.Generated.Emitted).source
    assertContains(source, "@Preview(name = \"3x2\", widthDp = 203, heightDp = 220)")
  }

  @Test
  fun `a theme colour is refused rather than written as Remote Material 3`() {
    val document =
      json.decodeFromString(
        UiBuilderDocument.serializer(),
        COUNTER_WIDGET.replace("\"#FF1D1B20\"", "\"onSurface\""),
      )
    val generated = export(document)
    assertIs<RecordFreeExport.Generated.Refused>(generated)
    assertTrue(generated.reasons.any { "Remote Material 3" in it }, generated.reasons.toString())
  }

  @Test
  fun `the grid maps cell counts to Android's documented portrait sizes`() {
    assertEquals(LauncherWidgetGrid.Size(3, 2), LauncherWidgetGrid.of(203, 220))
    assertEquals(LauncherWidgetGrid.Size(2, 1), LauncherWidgetGrid.of(130, 102))
    assertEquals(LauncherWidgetGrid.Size(1, 1), LauncherWidgetGrid.of(57, 102))
    assertEquals(349 to 220, LauncherWidgetGrid.Size(5, 2).let { it.widthDp to it.heightDp })
    assertNull(LauncherWidgetGrid.of(216, 76))
    assertEquals(LauncherWidgetGrid.Size(4, 2), LauncherWidgetGrid.parse("4x2"))
    assertNull(LauncherWidgetGrid.parse("0x2"))
  }

  /** `WidgetButton`, as the catalog's record names it. */
  private val records =
    mapOf(
      "remote-widgets/widget-button" to
        ComponentRecord(
          canonicalId = "widget-catalog/ee.schimke.remotewidgets.WidgetComponentsKt.WidgetButton",
          componentIds = listOf("remote-widgets/widget-button"),
          symbol =
            ComponentSymbol(
              jvmOwner = "ee.schimke.remotewidgets.WidgetComponentsKt",
              callable = "ee.schimke.remotewidgets.WidgetButton",
              name = "WidgetButton",
              origin = ComponentOrigin.LIBRARY,
            ),
          parameters =
            listOf(
              TargetParameter(name = "text", type = "String", typeFqn = "kotlin.String"),
              TargetParameter(
                name = "modifier",
                type = "RemoteModifier",
                typeFqn = "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
                hasDefault = true,
              ),
            ),
          slots = emptyList(),
          signatureKnown = true,
        )
    )

  private companion object {
    /** `counter-widget.json`, as the catalog publishes it. */
    val COUNTER_WIDGET =
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "counter-widget",
        "title": "Counter widget · 3x2 (203×220dp)",
        "revision": 0,
        "template": "counter-widget",
        "catalogPin": {
          "systemId": "remote-widgets",
          "catalogRevision": "candidate",
          "capabilityDigest": "candidate",
          "nativeRuntimeId": "candidate"
        },
        "environment": {
          "widthDp": 203,
          "heightDp": 220,
          "density": 2.75,
          "theme": "light",
          "dynamicColor": false,
          "locale": "en-US",
          "fontScale": 1,
          "layoutDirection": "ltr",
          "windowPosture": "flat",
          "animations": "settled",
          "networkAccess": false
        },
        "stateVariables": {},
        "roots": ["launcher-widget"],
        "nodes": {
          "launcher-widget": {
            "id": "launcher-widget",
            "componentId": "remote-widgets/launcher-widget",
            "properties": { "background": { "type": "string", "value": "#FFF3EDF7" } },
            "modifiers": [],
            "slots": { "content": ["counter-row"] }
          },
          "counter-row": {
            "id": "counter-row",
            "componentId": "layout/row",
            "properties": {
              "verticalAlignment": { "type": "string", "value": "centerVertically" }
            },
            "modifiers": [{ "type": "fillMaxSize" }],
            "slots": { "children": ["minus", "count", "plus"] }
          },
          "minus": {
            "id": "minus",
            "componentId": "remote-widgets/widget-button",
            "properties": { "text": { "type": "string", "value": "-" } },
            "modifiers": [{ "type": "weight", "weight": 1 }],
            "slots": {}
          },
          "count": {
            "id": "count",
            "componentId": "remote-widgets/remote-text",
            "properties": {
              "text": { "type": "string", "value": "0" },
              "fontSize": { "type": "float", "value": 48 },
              "color": { "type": "string", "value": "#FF1D1B20" }
            },
            "modifiers": [],
            "slots": {}
          },
          "plus": {
            "id": "plus",
            "componentId": "remote-widgets/widget-button",
            "properties": { "text": { "type": "string", "value": "+" } },
            "modifiers": [{ "type": "weight", "weight": 1 }],
            "slots": {}
          }
        }
      }
      """
        .trimIndent()
  }
}
