package ee.schimke.composeai.uibuilder

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.uibuilder.editor.ContrastKind
import ee.schimke.composeai.uibuilder.editor.WcagGrade
import ee.schimke.composeai.uibuilder.editor.contrastRatio
import ee.schimke.composeai.uibuilder.editor.contrastReading
import ee.schimke.composeai.uibuilder.editor.formatContrastRatio
import ee.schimke.composeai.uibuilder.editor.isForegroundColorProperty
import ee.schimke.composeai.uibuilder.editor.relativeLuminance
import ee.schimke.composeai.uibuilder.editor.wcagGrade
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The inspector's contrast chip: WCAG's arithmetic against pairs whose ratios are published, and
 * the walk that decides which background a colour is measured against — including when it refuses
 * to guess.
 */
class ColorContrastTest {
  private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.01) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

  @Test
  fun luminanceSpansBlackToWhite() {
    assertClose(0.0, relativeLuminance(Color.Black), 1e-9)
    assertClose(1.0, relativeLuminance(Color.White), 1e-6)
  }

  @Test
  fun knownPairs() {
    assertClose(21.0, contrastRatio(Color.Black, Color.White))
    // Symmetric: which one is the text does not change the ratio.
    assertClose(21.0, contrastRatio(Color.White, Color.Black))
    assertClose(1.0, contrastRatio(Color(0xFF777777), Color(0xFF777777)))
    // WebAIM's published values.
    assertClose(4.48, contrastRatio(Color(0xFF777777), Color.White))
    assertClose(4.54, contrastRatio(Color(0xFF767676), Color.White))
    assertClose(3.99, contrastRatio(Color(0xFFFF0000), Color.White))
    assertClose(2.44, contrastRatio(Color(0xFF0000FF), Color.Black))
  }

  @Test
  fun gradesFollowTheTextSize() {
    assertEquals(WcagGrade.AAA, wcagGrade(21.0, largeText = false))
    assertEquals(WcagGrade.AAA, wcagGrade(7.0, largeText = false))
    assertEquals(WcagGrade.AA, wcagGrade(4.5, largeText = false))
    assertEquals(WcagGrade.Low, wcagGrade(4.48, largeText = false))
    assertEquals(WcagGrade.AAA, wcagGrade(4.5, largeText = true))
    assertEquals(WcagGrade.AA, wcagGrade(3.0, largeText = true))
    assertEquals(WcagGrade.Low, wcagGrade(2.99, largeText = true))
    // Icons are non-text content (1.4.11): one 3:1 bar, and no AAA however high the ratio.
    assertEquals(WcagGrade.AA, wcagGrade(21.0, ContrastKind.NonText))
    assertEquals(WcagGrade.AA, wcagGrade(3.0, ContrastKind.NonText))
    assertEquals(WcagGrade.Low, wcagGrade(2.99, ContrastKind.NonText))
  }

  @Test
  fun ratioIsTruncatedNotRounded() {
    assertEquals("21:1", formatContrastRatio(21.0))
    assertEquals("4.5:1", formatContrastRatio(4.54))
    // 4.48 must not read as "4.5:1" beside a "Low" grade.
    assertEquals("4.4:1", formatContrastRatio(4.48))
    assertEquals("1:1", formatContrastRatio(1.0))
  }

  @Test
  fun onlyTextAndIconColoursAreForegrounds() {
    assertTrue(isForegroundColorProperty("m3/text", "color"))
    assertTrue(isForegroundColorProperty("wear-m3/icon", "color"))
    assertTrue(isForegroundColorProperty("remote-m3/remote-time-text", "color"))
    assertTrue(isForegroundColorProperty("m3/surface", "contentColor"))
    assertTrue(isForegroundColorProperty("m3/top-app-bar", "titleContentColor"))
    assertFalse(isForegroundColorProperty("m3/horizontal-divider", "color"))
    assertFalse(isForegroundColorProperty("draw/rect", "color"))
    assertFalse(isForegroundColorProperty("m3/card", "containerColor"))
  }

  private val scheme = lightColorScheme(surface = Color.White, primary = Color(0xFF6750A4))

  private fun color(value: String) =
    JsonObject(
      mapOf(
        "type" to JsonPrimitive(if (value.startsWith("#")) "color" else "colorToken"),
        "value" to JsonPrimitive(value),
      )
    )

  private fun enum(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun bool(value: Boolean) =
    JsonObject(mapOf("type" to JsonPrimitive("boolean"), "value" to JsonPrimitive(value)))

  private fun number(value: Double) =
    JsonObject(mapOf("type" to JsonPrimitive("number"), "value" to JsonPrimitive(value)))

  private fun backgroundModifier(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("background"), "color" to color(value)))

  private fun node(
    id: String,
    componentId: String,
    properties: Map<String, JsonObject> = emptyMap(),
    children: List<String> = emptyList(),
    modifiers: List<JsonObject> = emptyList(),
  ) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = JsonObject(properties),
      modifiers = JsonArray(modifiers),
      slots = if (children.isEmpty()) emptyMap() else mapOf("content" to children),
    )

  private fun document(vararg nodes: UiBuilderNode) =
    UiBuilderDocument(
      schema = "test",
      id = "doc",
      title = "Contrast",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(nodes.first().id),
      nodes = nodes.associateBy { it.id },
    )

  @Test
  fun textOverNoContainerIsMeasuredAgainstTheThemeSurface() {
    val doc =
      document(node("column", "layout/column", children = listOf("t")), node("t", "m3/text"))
    val reading = assertNotNull(contrastReading(doc, "t", "color", "#FF000000", scheme))
    assertClose(21.0, reading.ratio)
    assertEquals("21:1 AAA", reading.label)
    assertEquals("the theme surface", reading.backgroundSource)
  }

  @Test
  fun nearestAncestorContainerWins() {
    val doc =
      document(
        node(
          "card",
          "m3/card",
          mapOf("containerColor" to color("#FF000000")),
          children = listOf("column"),
        ),
        node("column", "layout/column", children = listOf("t")),
        node("t", "m3/text"),
      )
    val reading = assertNotNull(contrastReading(doc, "t", "color", "#FF333333", scheme))
    assertEquals(WcagGrade.Low, reading.grade)
    assertTrue(reading.backgroundSource.contains("card"), reading.backgroundSource)
    assertTrue(reading.explanation.startsWith("Contrast "), reading.explanation)
  }

  @Test
  fun aComponentsOwnContainerIsItsBackground() {
    val doc = document(node("s", "m3/surface", mapOf("containerColor" to color("#FFFFFFFF"))))
    val reading = assertNotNull(contrastReading(doc, "s", "contentColor", "#FF767676", scheme))
    assertEquals(WcagGrade.AA, reading.grade)
    assertTrue(reading.backgroundSource.startsWith("its own container"))
  }

  @Test
  fun anUnsetButtonContainerIsItsPrimaryDefault() {
    val doc = document(node("b", "m3/button", children = listOf("t")), node("t", "m3/text"))
    val reading = assertNotNull(contrastReading(doc, "t", "color", "onPrimary", scheme))
    assertClose(contrastRatio(scheme.onPrimary, scheme.primary), reading.ratio)
  }

  @Test
  fun displayTextIsGradedAsLarge() {
    val doc = document(node("t", "m3/text", mapOf("style" to enum("displaySmall"))))
    // #949494 on white is about 3:1 — AA only for large text.
    val reading = assertNotNull(contrastReading(doc, "t", "color", "#FF949494", scheme))
    assertEquals(ContrastKind.LargeText, reading.kind)
    assertEquals(WcagGrade.AA, reading.grade)
  }

  @Test
  fun noBadgeWhenTheBackgroundCannotBeKnown() {
    val bound =
      JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive("bg")))
    val doc =
      document(
        node("box", "m3/surface", mapOf("containerColor" to bound), children = listOf("t")),
        node("t", "m3/text"),
        node("w", "wear-m3/button", children = listOf("wt")),
        node("wt", "wear-m3/text"),
      )
    assertNull(contrastReading(doc, "t", "color", "#FF000000", scheme))
    // Wear draws in its own scheme; the inspector's would be a guess.
    assertNull(contrastReading(doc, "wt", "color", "#FF000000", scheme))
    // A fill, not a foreground.
    assertNull(contrastReading(doc, "box", "containerColor", "#FF000000", scheme))
    // A value that is not a colour.
    assertNull(contrastReading(doc, "t", "color", "", scheme))
  }

  @Test
  fun aComponentOutsideThePackagedFloorIsDeclined() {
    // A catalog the inspector knows nothing about: even a literal container colour is not trusted,
    // because that catalog's own renderer decides what it draws.
    val white =
      JsonObject(mapOf("type" to JsonPrimitive("color"), "value" to JsonPrimitive("#FFFFFFFF")))
    val doc =
      document(
        node("card", "acme/card", mapOf("containerColor" to white), children = listOf("t")),
        node("t", "m3/text"),
      )
    assertNull(contrastReading(doc, "t", "color", "#FF000000", scheme))
  }

  @Test
  fun iconsAreGradedAsNonText() {
    val doc = document(node("i", "m3/icon"))
    val reading = assertNotNull(contrastReading(doc, "i", "color", "#FF000000", scheme))
    assertEquals(ContrastKind.NonText, reading.kind)
    assertEquals("21:1 AA", reading.label)
  }

  @Test
  fun aBackgroundModifierIsWhatTheTextSitsOn() {
    val doc =
      document(
        node(
          "box",
          "layout/box",
          children = listOf("t"),
          modifiers = listOf(backgroundModifier("#FF000000")),
        ),
        node("t", "m3/text"),
      )
    // Black text on the box's black background — not on the white theme surface.
    val reading = assertNotNull(contrastReading(doc, "t", "color", "#FF000000", scheme))
    assertClose(1.0, reading.ratio)
    assertEquals(WcagGrade.Low, reading.grade)
    // The text's own background modifier is nearer still.
    val own = document(node("t", "m3/text", modifiers = listOf(backgroundModifier("#FF000000"))))
    assertClose(21.0, assertNotNull(contrastReading(own, "t", "color", "#FFFFFFFF", scheme)).ratio)
  }

  @Test
  fun theLastBackgroundModifierAndThenTheContainerAreOnTop() {
    val doc =
      document(
        node(
          "s",
          "m3/surface",
          mapOf("containerColor" to color("#FFFFFFFF")),
          modifiers = listOf(backgroundModifier("#FF000000")),
          children = listOf("t"),
        ),
        node("t", "m3/text"),
        node(
          "b",
          "layout/box",
          modifiers = listOf(backgroundModifier("#FFFFFFFF"), backgroundModifier("#FF000000")),
          children = listOf("u"),
        ),
        node("u", "m3/text"),
      )
    assertClose(21.0, assertNotNull(contrastReading(doc, "t", "color", "#FF000000", scheme)).ratio)
    assertClose(21.0, assertNotNull(contrastReading(doc, "u", "color", "#FFFFFFFF", scheme)).ratio)
  }

  @Test
  fun noBadgeUnderAnUnreadableModifier() {
    val bound =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("background"),
          "color" to
            JsonObject(mapOf("type" to JsonPrimitive("state"), "variable" to JsonPrimitive("c"))),
        )
      )
    val faded = JsonObject(mapOf("type" to JsonPrimitive("alpha"), "alpha" to JsonPrimitive(0.5)))
    val doc =
      document(
        node("b", "layout/box", modifiers = listOf(bound), children = listOf("t")),
        node("t", "m3/text"),
        node("f", "layout/box", modifiers = listOf(faded), children = listOf("u")),
        node("u", "m3/text"),
      )
    assertNull(contrastReading(doc, "t", "color", "#FF000000", scheme))
    assertNull(contrastReading(doc, "u", "color", "#FF000000", scheme))
  }

  @Test
  fun aButtonsContainerFollowsItsStyle() {
    fun reading(vararg properties: Pair<String, JsonObject>) =
      contrastReading(
        document(node("b", "m3/button", mapOf(*properties), listOf("t")), node("t", "m3/text")),
        "t",
        "color",
        "#FF000000",
        scheme,
      )
    // A text button draws no container, even with a containerColor written: the surface shows.
    assertClose(21.0, assertNotNull(reading("style" to enum("text"))).ratio)
    assertClose(
      21.0,
      assertNotNull(reading("style" to enum("text"), "containerColor" to color("#FF000000"))).ratio,
    )
    assertClose(
      contrastRatio(Color.Black, scheme.primary),
      assertNotNull(reading("style" to enum("fab"))).ratio,
    )
    // Material's tonal container, and its disabled colours, are not the preview scheme's.
    assertNull(reading("style" to enum("filledTonal")))
    assertNull(reading("enabled" to bool(false)))
  }

  @Test
  fun aTokensSizeAndWeightDecideLargeText() {
    fun kind(vararg properties: Pair<String, JsonObject>) =
      assertNotNull(
          contrastReading(
            document(node("t", "m3/text", mapOf(*properties))),
            "t",
            "color",
            "#FF000000",
            scheme,
          )
        )
        .kind
    assertEquals(ContrastKind.LargeText, kind("style" to enum("headlineSmall")))
    // titleLarge is 22sp: large only when bold.
    assertEquals(ContrastKind.BodyText, kind("style" to enum("titleLarge")))
    assertEquals(
      ContrastKind.LargeText,
      kind("style" to enum("titleLarge"), "fontWeight" to enum("bold")),
    )
    assertEquals(
      ContrastKind.BodyText,
      kind("style" to enum("bodyLarge"), "fontWeight" to enum("bold")),
    )
    assertEquals(
      ContrastKind.LargeText,
      kind("fontSizeSp" to number(19.0), "fontWeight" to enum("bold")),
    )
    assertEquals(ContrastKind.BodyText, kind())
  }
}
