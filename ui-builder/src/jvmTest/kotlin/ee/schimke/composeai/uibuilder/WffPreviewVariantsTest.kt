package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.WffComplicationPick
import ee.schimke.composeai.uibuilder.canvas.WffComplicationSample
import ee.schimke.composeai.uibuilder.canvas.WffPreviewContext
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import ee.schimke.composeai.uibuilder.canvas.WffSimulatedState
import ee.schimke.composeai.uibuilder.canvas.WffValue
import ee.schimke.composeai.uibuilder.canvas.evaluateWffExpression
import ee.schimke.composeai.uibuilder.canvas.isWffAmbient
import ee.schimke.composeai.uibuilder.canvas.wffConfigurations
import ee.schimke.composeai.uibuilder.canvas.wffFlavors
import ee.schimke.composeai.uibuilder.editor.LiveExpressionResult
import ee.schimke.composeai.uibuilder.editor.WffPreviewAmbientMode
import ee.schimke.composeai.uibuilder.editor.WffPreviewComplicationPreset
import ee.schimke.composeai.uibuilder.editor.WffPreviewFanOut
import ee.schimke.composeai.uibuilder.editor.defaultWffPreviewFanOut
import ee.schimke.composeai.uibuilder.editor.evaluateLiveFormula
import ee.schimke.composeai.uibuilder.editor.evaluateLiveWffProperty
import ee.schimke.composeai.uibuilder.editor.offersWearTilePane
import ee.schimke.composeai.uibuilder.editor.watchFacePreviewPanes
import ee.schimke.composeai.uibuilder.editor.wearScreenPanes
import ee.schimke.composeai.uibuilder.editor.wffComplicationPresetPicks
import ee.schimke.composeai.uibuilder.editor.wffPreviewFanOutChoices
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WffPreviewVariantsTest {

  private fun importFace(xml: String): UiBuilderDocument {
    val imported = WatchFaceXmlImporter.import(xml)
    assertIs<WatchFaceXmlImporter.Result.Imported>(imported)
    return imported.document
  }

  private val multiConfigFaceXml =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <UserConfigurations>
        <ColorConfiguration id="theme" displayName="Colour theme" defaultValue="ocean">
          <ColorOption id="ocean" displayName="Ocean" colors="#FF00BCD4 #FF006064" />
          <ColorOption id="amber" displayName="Amber" colors="#FFFFC107 #FF6D4C41" />
          <ColorOption id="rose" displayName="Rose" colors="#FFE91E63 #FF880E4F" />
        </ColorConfiguration>
        <ListConfiguration id="layout" displayName="Dial layout" defaultValue="minimal">
          <ListOption id="minimal" displayName="Minimal" />
          <ListOption id="chrono" displayName="Chronograph" />
        </ListConfiguration>
        <BooleanConfiguration id="show_ticks" displayName="Show ticks" defaultValue="TRUE" />
        <Flavors defaultValue="sport">
          <Flavor id="sport" displayName="Sport">
            <Configuration id="theme" optionId="amber" />
            <Configuration id="layout" optionId="chrono" />
          </Flavor>
          <Flavor id="classic" displayName="Classic">
            <Configuration id="theme" optionId="ocean" />
            <Configuration id="layout" optionId="minimal" />
          </Flavor>
        </Flavors>
      </UserConfigurations>
      <Scene backgroundColor="#FF000000">
        <ComplicationSlot x="175" y="300" width="100" height="100" slotId="0"
            supportedTypes="SHORT_TEXT RANGED_VALUE"
            isCustomizable="TRUE">
          <BoundingOval x="0" y="0" width="100" height="100" />
          <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY"
              defaultSystemProviderType="RANGED_VALUE" />
          <Complication type="SHORT_TEXT">
            <PartText x="0" y="0" width="100" height="100">
              <Text align="CENTER"><Font family="SYNC_TO_DEVICE" size="24" color="[CONFIGURATION.theme.0]">[COMPLICATION.TEXT]</Font></Text>
            </PartText>
          </Complication>
        </ComplicationSlot>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  @Test
  fun `default watch face preview fans out all colour styles at one normalized size`() {
    val doc =
      importFace(multiConfigFaceXml).let { imported ->
        // Even if multiple Wear devices are stored in exportDevices, watch faces ignore watch
        // sizes.
        imported.copy(
          environment =
            JsonObject(
              imported.environment +
                ("exportDevices" to
                  JsonArray(
                    listOf(
                      JsonPrimitive("id:wearos_small_round"),
                      JsonPrimitive("id:wearos_large_round"),
                      JsonPrimitive("id:wearos_square"),
                    ),
                  )),
            ),
        )
      }

    assertEquals(WffPreviewFanOut.Configuration("theme"), doc.defaultWffPreviewFanOut())
    val panes = doc.watchFacePreviewPanes()
    assertEquals(listOf("Ocean", "Amber", "Rose"), panes.map { it.label })
    assertTrue(panes.all { !it.document.isWffAmbient() })
    // Every pane shares the single normalized frame size.
    assertEquals(1, panes.map { it.widthDp to it.heightDp }.distinct().size)
    // Each pane sets its own colour option as defaultValue on its copy.
    assertEquals(
      listOf("ocean", "amber", "rose"),
      panes.map { pane ->
        pane.document.wffConfigurations().first { it.id == "theme" }.defaultValue
      },
    )
  }

  @Test
  fun `switching fan-out shows all layouts, flavors, booleans, or interactive and ambient`() {
    val doc = importFace(multiConfigFaceXml)

    val choices = doc.wffPreviewFanOutChoices().map { it.label }
    assertEquals(
      listOf("Colour theme", "Dial layout", "Flavors", "Show ticks", "Interactive & Ambient"),
      choices,
    )

    // Layouts side-by-side while keeping a base colour selection.
    val layoutPanes =
      doc.watchFacePreviewPanes(
        fanOut = WffPreviewFanOut.Configuration("layout"),
        baseSelection = mapOf("theme" to "rose"),
      )
    assertEquals(listOf("Minimal", "Chronograph"), layoutPanes.map { it.label })
    assertTrue(
      layoutPanes.all { pane ->
        pane.document.wffConfigurations().first { it.id == "theme" }.defaultValue == "rose"
      },
    )

    // Flavors side-by-side.
    assertEquals(listOf("sport", "classic"), doc.wffFlavors().map { it.id })
    val flavorPanes = doc.watchFacePreviewPanes(fanOut = WffPreviewFanOut.Flavors)
    assertEquals(listOf("Sport", "Classic"), flavorPanes.map { it.label })
    assertEquals(
      "amber",
      flavorPanes[0].document.wffConfigurations().first { it.id == "theme" }.defaultValue,
    )
    assertEquals(
      "chrono",
      flavorPanes[0].document.wffConfigurations().first { it.id == "layout" }.defaultValue,
    )

    // Ambient mode options (Both doubles each colour style into Interactive + Ambient).
    val bothPanes =
      doc.watchFacePreviewPanes(
        fanOut = WffPreviewFanOut.Configuration("theme"),
        ambientMode = WffPreviewAmbientMode.Both,
      )
    assertEquals(6, bothPanes.size)
    assertEquals(
      listOf(false, true, false, true, false, true),
      bothPanes.map { it.document.isWffAmbient() },
    )

    // Explicit Ambient fan-out.
    val ambientPanes = doc.watchFacePreviewPanes(fanOut = WffPreviewFanOut.Ambient)
    assertEquals(listOf("Interactive", "Ambient"), ambientPanes.map { it.label })
    assertEquals(listOf(false, true), ambientPanes.map { it.document.isWffAmbient() })
  }

  @Test
  fun `watch face does not offer Wear screen or tile panes and supports complication presets`() {
    val doc = importFace(multiConfigFaceXml)
    assertEquals(emptyList(), doc.wearScreenPanes())
    assertFalse(doc.offersWearTilePane())

    val custom = mapOf("slot-x" to WffComplicationPick(provider = "HEART_RATE"))
    assertEquals(
      custom,
      doc.wffComplicationPresetPicks(WffPreviewComplicationPreset.Default, custom),
    )
    val emptyPicks = doc.wffComplicationPresetPicks(WffPreviewComplicationPreset.Empty, custom)
    assertEquals(1, emptyPicks.size)
    assertEquals(WffComplicationSample.EMPTY, emptyPicks.values.single().type)
    val noDataPicks = doc.wffComplicationPresetPicks(WffPreviewComplicationPreset.NoData, custom)
    assertEquals(WffComplicationSample.NO_DATA, noDataPicks.values.single().type)
  }

  @Test
  fun `simulated watch state drives battery, steps, heart rate, 24h, and weather expressions`() {
    val simulated =
      WffSimulatedState(
        batteryPercent = 12,
        batteryCharging = true,
        stepCount = 2500,
        stepGoal = 10000,
        heartRate = 118,
        is24Hour = false,
        unreadNotifications = 7,
        weatherTemperature = 19,
        weatherCondition = 5,
        weatherConditionName = "Rain",
        weatherPrecipitationChance = 80,
      )
    val context = WffPreviewContext().withSimulated(simulated)

    assertEquals(WffValue.Num(12.0), evaluateWffExpression("[BATTERY_PERCENT]", context))
    assertEquals(WffValue.Num(1.0), evaluateWffExpression("[BATTERY_IS_LOW]", context))
    assertEquals(WffValue.Num(1.0), evaluateWffExpression("[BATTERY_CHARGING_STATUS]", context))
    assertEquals(WffValue.Num(2500.0), evaluateWffExpression("[STEP_COUNT]", context))
    assertEquals(WffValue.Num(25.0), evaluateWffExpression("[STEP_PERCENT]", context))
    assertEquals(WffValue.Num(118.0), evaluateWffExpression("[HEART_RATE]", context))
    assertEquals(WffValue.Num(0.0), evaluateWffExpression("[IS_24_HOUR_MODE]", context))
    assertEquals(WffValue.Num(7.0), evaluateWffExpression("[UNREAD_NOTIFICATION_COUNT]", context))
    assertEquals(WffValue.Num(19.0), evaluateWffExpression("[WEATHER.TEMPERATURE]", context))
    assertEquals(WffValue.Str("Rain"), evaluateWffExpression("[WEATHER.CONDITION_NAME]", context))
    assertEquals(
      WffValue.Num(80.0),
      evaluateWffExpression("[WEATHER.CHANCE_OF_PRECIPITATION]", context),
    )
  }

  @Test
  fun `live expression evaluator supports both general UiExpressions formulas and WFF expressions`() {
    // 1. General / Remote Compose UiExpressions formula
    val rcDoc =
      blankUiBuilderDocument(
          designId = "rc-test",
          environment =
            buildJsonObject {
              put("widthDp", 227)
              put("heightDp", 227)
              put("density", 2.0)
              put("fixedTime", "2024-05-16T10:08:30Z")
            },
          catalogPin = JsonObject(emptyMap()),
        )
        .copy(
          stateVariables =
            buildJsonObject {
              put(
                "count",
                buildJsonObject {
                  put("valueType", "int")
                  put("initialValue", 4)
                },
              )
            },
        )
    assertEquals(
      LiveExpressionResult.Value("3060"),
      evaluateLiveFormula(rcDoc, "time.secondOfHour * 6", "angle"),
    )
    assertEquals(
      LiveExpressionResult.Value("14"),
      evaluateLiveFormula(rcDoc, "count + 10", "total"),
    )
    assertIs<LiveExpressionResult.Error>(evaluateLiveFormula(rcDoc, "unknownVar + 1", "bad"))
    assertNull(evaluateLiveFormula(rcDoc, "   ", "empty"))

    // 2. WFF property live evaluation
    val wffDoc = importFace(multiConfigFaceXml)
    assertNull(evaluateLiveWffProperty(wffDoc, "plain text"))
    assertEquals(
      LiveExpressionResult.Value("#FF00BCD4"),
      evaluateLiveWffProperty(wffDoc, "[CONFIGURATION.theme.0]"),
    )
    assertEquals(
      LiveExpressionResult.Value("90"),
      evaluateLiveWffProperty(
        wffDoc,
        "[SECOND] * 6",
        scrubbedTime = WffScrubTime(10, 8, 15.0),
      ),
    )
    assertEquals(
      LiveExpressionResult.Value("42"),
      evaluateLiveWffProperty(
        wffDoc,
        "[BATTERY_PERCENT]",
        simulated = WffSimulatedState(batteryPercent = 42),
      ),
    )
  }
}
