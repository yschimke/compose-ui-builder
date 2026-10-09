package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecord
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.Json

/**
 * The slot the canvas draws must be the slot the widget shows, so these are remote-m3-catalog's
 * `AdaptiveWidgetTest` and `AdaptiveLayoutTest` cases asked of the builder's copy of the rule.
 */
class LauncherAdaptiveLayoutTest {

  private fun shown(
    widthDp: Float,
    heightDp: Float,
    sizes: Map<String, String> = emptyMap(),
    filled: Set<String> = setOf("compact", "medium", "expanded"),
  ) = LauncherAdaptiveLayout.visibleSlot(widthDp, heightDp, sizes::get, filled::contains)

  private fun at(label: String) = LauncherWidgetGrid.parse(label)!!

  private fun shown(label: String, filled: Set<String> = setOf("compact", "medium", "expanded")) =
    at(label).let { shown(it.widthDp.toFloat(), it.heightDp.toFloat(), filled = filled) }

  @Test
  fun `each breakpoint shows its own slot`() {
    assertEquals("compact", shown("2x1"))
    assertEquals("medium", shown("4x1"))
    assertEquals("expanded", shown("4x2"))
  }

  @Test
  fun `between breakpoints, the closest one that fits`() {
    assertEquals("compact", shown("3x1"))
    assertEquals("compact", shown("3x2"))
    assertEquals("expanded", shown("5x2"))
    assertEquals("medium", shown(349f, 150f))
  }

  @Test
  fun `tall but narrow fits only compact, and nothing fitting falls back to the smallest`() {
    assertEquals("compact", shown("2x2"))
    assertEquals("compact", shown("1x1"))
  }

  @Test
  fun `a frame a fraction of a dp short still fits`() {
    assertEquals("medium", shown(275.7f, 101.8f))
  }

  @Test
  fun `an empty slot is not a breakpoint, compact included`() {
    assertEquals("expanded", shown("2x1", filled = setOf("expanded")))
    assertEquals("expanded", shown("4x2", filled = setOf("expanded")))
    assertEquals("medium", shown("4x2", filled = setOf("compact", "medium")))
    assertEquals("compact", shown("4x2", filled = emptySet()))
  }

  @Test
  fun `sizes are the design's, and two slots at one size are the earlier slot's`() {
    assertEquals("expanded", shown(203f, 102f, sizes = mapOf("expandedSize" to "3x1")))
    assertEquals(
      "compact",
      shown(276f, 102f, sizes = mapOf("compactSize" to "4x1", "mediumSize" to "4x1")),
    )
  }

  @Test
  fun `it exports as a call to the catalog's AdaptiveLayout, one named slot per breakpoint`() {
    val json = Json { ignoreUnknownKeys = true }
    val document = json.decodeFromString(UiBuilderDocument.serializer(), ADAPTIVE_WIDGET)
    val records =
      mapOf(
        "remote-widgets/adaptive-layout" to
          json.decodeFromString(ComponentRecord.serializer(), ADAPTIVE_LAYOUT_RECORD),
        "remote-widgets/widget-title" to
          json.decodeFromString(ComponentRecord.serializer(), WIDGET_TITLE_RECORD),
      )
    val source =
      assertIs<LauncherWidgetCodeExporter.Result.Emitted>(
          LauncherWidgetCodeExporter.export(document, "adaptive", records)
        )
        .source
    assertContains(source, "import ee.schimke.remotewidgets.AdaptiveLayout")
    assertContains(source, "AdaptiveLayout(")
    assertContains(source, "mediumSize = \"4x1\"")
    assertContains(source, "compact = {")
    assertContains(source, "WidgetTitle(text = \"Compact\")")
    // The design left `expanded` empty, so the call leaves it to the catalog's no-layout default
    // and 4x2 is not a breakpoint of this widget.
    assertFalse("expanded =" in source, source)
  }

  private companion object {
    /**
     * A 4x1 widget whose adaptive layout fills `compact` and `medium` and leaves `expanded` empty.
     */
    val ADAPTIVE_WIDGET =
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "adaptive-widget",
        "title": "Adaptive widget · 4x1 (276×102dp)",
        "revision": 0,
        "catalogPin": {
          "systemId": "remote-widgets",
          "catalogRevision": "candidate",
          "capabilityDigest": "candidate",
          "nativeRuntimeId": "candidate"
        },
        "environment": {"widthDp": 276, "heightDp": 102, "density": 2.75, "theme": "light"},
        "stateVariables": {},
        "roots": ["launcher-widget"],
        "nodes": {
          "launcher-widget": {
            "id": "launcher-widget",
            "componentId": "remote-widgets/launcher-widget",
            "properties": {},
            "modifiers": [],
            "slots": {"content": ["adaptive"]}
          },
          "adaptive": {
            "id": "adaptive",
            "componentId": "remote-widgets/adaptive-layout",
            "properties": {"mediumSize": {"type": "string", "value": "4x1"}},
            "modifiers": [],
            "slots": {"compact": ["compact-title"], "medium": ["medium-title"]}
          },
          "compact-title": {
            "id": "compact-title",
            "componentId": "remote-widgets/widget-title",
            "properties": {"text": {"type": "string", "value": "Compact"}},
            "modifiers": [],
            "slots": {}
          },
          "medium-title": {
            "id": "medium-title",
            "componentId": "remote-widgets/widget-title",
            "properties": {"text": {"type": "string", "value": "Medium"}},
            "modifiers": [],
            "slots": {}
          }
        }
      }
      """
        .trimIndent()

    /**
     * remote-m3-catalog's `components.json` entry for `AdaptiveLayout`, bindings and code aside.
     */
    val ADAPTIVE_LAYOUT_RECORD =
      """
      {
        "canonicalId": "widget-catalog/ee.schimke.remotewidgets.AdaptiveLayoutKt.AdaptiveLayout",
        "componentIds": [
          "AdaptiveLayout"
        ],
        "symbol": {
          "jvmOwner": "ee.schimke.remotewidgets.AdaptiveLayoutKt",
          "callable": "ee.schimke.remotewidgets.AdaptiveLayout",
          "name": "AdaptiveLayout",
          "origin": "PROJECT",
          "jvmName": "AdaptiveLayout",
          "descriptor": "(Landroidx/compose/remote/creation/compose/modifier/RemoteModifier;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;II)V",
          "sourceFile": "src/main/kotlin/ee/schimke/remotewidgets/AdaptiveLayout.kt",
          "docs": "unavailable",
          "receiver": null
        },
        "parameters": [
          {
            "name": "modifier",
            "type": "RemoteModifier",
            "typeFqn": "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "compactSize",
            "type": "String",
            "typeFqn": "kotlin.String",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "mediumSize",
            "type": "String",
            "typeFqn": "kotlin.String",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "expandedSize",
            "type": "String",
            "typeFqn": "kotlin.String",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "compact",
            "type": "() -> Unit",
            "typeFqn": "kotlin.Function0",
            "hasDefault": true,
            "composableSlot": true,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": "kotlin.Unit"
          },
          {
            "name": "medium",
            "type": "() -> Unit",
            "typeFqn": "kotlin.Function0",
            "hasDefault": true,
            "composableSlot": true,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": "kotlin.Unit"
          },
          {
            "name": "expanded",
            "type": "() -> Unit",
            "typeFqn": "kotlin.Function0",
            "hasDefault": true,
            "composableSlot": true,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": "kotlin.Unit"
          }
        ],
        "slots": [
          {
            "name": "compact",
            "required": false,
            "receiverScope": null
          },
          {
            "name": "medium",
            "required": false,
            "receiverScope": null
          },
          {
            "name": "expanded",
            "required": false,
            "receiverScope": null
          }
        ],
        "signatureKnown": true,
        "callableFromAnotherFile": true,
        "hasTypeParameters": false,
        "hasContextReceivers": false,
        "overloadsCollided": false,
        "builder": null,
        "requiredOptIns": [],
        "androidxOptIns": []
      }
      """
        .trimIndent()

    /** The same file's `WidgetTitle`. */
    val WIDGET_TITLE_RECORD =
      """
      {
        "canonicalId": "widget-catalog/ee.schimke.remotewidgets.WidgetComponentsKt.WidgetTitle",
        "componentIds": [
          "WidgetTitle"
        ],
        "symbol": {
          "jvmOwner": "ee.schimke.remotewidgets.WidgetComponentsKt",
          "callable": "ee.schimke.remotewidgets.WidgetTitle",
          "name": "WidgetTitle",
          "origin": "PROJECT",
          "jvmName": "WidgetTitle-FNF3uiM",
          "descriptor": "(Ljava/lang/String;Landroidx/compose/remote/creation/compose/modifier/RemoteModifier;JLandroidx/compose/runtime/Composer;II)V",
          "sourceFile": "src/main/kotlin/ee/schimke/remotewidgets/WidgetComponents.kt",
          "docs": "unavailable",
          "receiver": null
        },
        "parameters": [
          {
            "name": "text",
            "type": "String",
            "typeFqn": "kotlin.String",
            "hasDefault": false,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "modifier",
            "type": "RemoteModifier",
            "typeFqn": "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          },
          {
            "name": "color",
            "type": "Color",
            "typeFqn": "androidx.compose.ui.graphics.Color",
            "hasDefault": true,
            "composableSlot": false,
            "composableSlotReceiver": null,
            "nullable": false,
            "noArgConstructible": false,
            "noArgFactory": null,
            "scopeDslReceiver": null,
            "lambdaReturnTypeFqn": null
          }
        ],
        "slots": [],
        "signatureKnown": true,
        "callableFromAnotherFile": true,
        "hasTypeParameters": false,
        "hasContextReceivers": false,
        "overloadsCollided": false,
        "builder": null,
        "requiredOptIns": [],
        "androidxOptIns": []
      }
      """
        .trimIndent()
  }
}
