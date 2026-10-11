package ee.schimke.composeai.uibuilder

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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

  private fun node(
    id: String,
    componentId: String,
    properties: Map<String, JsonObject> = emptyMap(),
    children: List<String> = emptyList(),
  ) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = JsonObject(properties),
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
    assertTrue(reading.largeText)
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
}
