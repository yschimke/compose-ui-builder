package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class GuidelineReviewFixesTest {
  @Test
  fun `the device picture speaks of a watch only on Wear`() {
    val wear = DesignGuidelinePicture.device(192, 192, "data:,", platform = "wear").description
    val glasses =
      DesignGuidelinePicture.device(640, 480, "data:,", platform = "glasses").description
    assertTrue("edge button" in wear, wear)
    assertFalse("edge button" in glasses || "Wear" in glasses || "watch" in glasses, glasses)
  }

  @Test
  fun `an unrolled frame covers the measured content, or says a longer list is cut`() {
    val measured = DesignGuidelineFrames.unrolled(192, 192, contentHeightDp = 1400, "wear")
    assertEquals(1400, measured.heightDp)
    assertTrue("tall enough for its whole list" in measured.describe(2))
    val fixed = DesignGuidelineFrames.unrolled(192, 192, contentHeightDp = null, "wear")
    assertEquals(768, fixed.heightDp)
    assertTrue("cut at the picture's bottom edge" in fixed.describe(2), fixed.describe(2))
    assertFalse("its whole list" in fixed.describe(2))
  }

  @Test
  fun `a catalog's own launcher root is a widget, and Remote inline content is neither`() {
    val renamed = encoded(document("remote-widgets", "acme/launcher-root"))
    assertEquals(
      DesignGuidelineRule.SURFACE_WIDGET,
      DesignGuidelinePrompt.surfaceOf(renamed, setOf("acme/launcher-root")),
    )
    val clock = encoded(document("remote-m3", "layout/box"))
    assertEquals(DesignGuidelineRule.SURFACE_CONTENT, DesignGuidelinePrompt.surfaceOf(clock))
    val general = rule("general", surfaces = listOf("screen", "widget"))
    val screenOnly = rule("scaffold", surfaces = listOf("screen"))
    assertTrue(general.appliesTo(DesignGuidelineRule.SURFACE_CONTENT))
    assertFalse(screenOnly.appliesTo(DesignGuidelineRule.SURFACE_CONTENT))
    val wearScreen = encoded(document("wear-m3", "wear-m3/screen-scaffold"))
    assertEquals(DesignGuidelineRule.SURFACE_SCREEN, DesignGuidelinePrompt.surfaceOf(wearScreen))
  }

  @Test
  fun `mobile visual rules wait for both the phone and the tablet picture`() {
    val doc = encoded(document("m3-catalog", "layout/column", 412, 915))
    val oneThumbnail =
      DesignGuidelinePrompt.prepare(DesignGuidelineRuleSet.Bundled, "d", 1, doc, "data:,", null)
    assertTrue(oneThumbnail.rules.asked.none { it.visual })
    assertTrue(oneThumbnail.rules.visualSkipped > 0)
    val both =
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "d",
        1,
        doc,
        listOf(
          DesignGuidelinePicture(DesignGuidelinePicture.PHONE, "p", 412, 915, "data:,"),
          DesignGuidelinePicture(DesignGuidelinePicture.TABLET, "t", 1280, 800, "data:,"),
        ),
        null,
      )
    assertTrue(both.rules.asked.any { it.visual })
  }

  @Test
  fun `catalog visual rules wait for every picture the catalog plans`() {
    val guidelines =
      CatalogGuidelines(
        catalog = "m3-catalog",
        platform = "mobile",
        version = 1,
        frames =
          listOf(
            CatalogGuidelineFrame("sized", label = "phone", widthDp = 412, heightDp = 915),
            CatalogGuidelineFrame("sized", label = "tablet", widthDp = 1280, heightDp = 800),
          ),
        rules = listOf(rule("compare", kind = "visual"), rule("tree")),
      )
    val doc = encoded(document("m3-catalog", "layout/column"))
    fun asked(vararg kinds: String) =
      DesignGuidelinePrompt.prepare(
          guidelines,
          "d",
          1,
          doc,
          kinds.map { DesignGuidelinePicture(it, it, 1, 1, "data:,") },
          null,
        )
        .rules
        .asked
        .map { it.id }
    assertEquals(listOf("tree"), asked("phone"))
    assertEquals(listOf("compare", "tree"), asked("phone", "tablet"))
  }

  @Test
  fun `triage keeps every rule when the design is too long for it`() {
    val rules = (1..20).map { rule("rule-$it") }
    val request =
      DesignGuidelineRequest(
        revision = 1,
        platform = "wear",
        rules = DesignGuidelineRequestRules(1, "s", rules.size, rules),
        systemPrompt = "",
        userText = "Design tree:\n" + "x".repeat(200_000) + "\nRules:\n- ruleId: rule-1\n",
        responseSchema = DesignGuidelinePrompt.responseSchema,
      )
    val state = DesignGuidelinePrompt.triageBody(request)["state"]!!.jsonObject
    val rulesText = state["rules"].toString()
    rules.forEach { assertTrue(it.id in rulesText, it.id) }
    assertTrue(state["design"].toString().length < 200_000)
  }

  private fun rule(
    id: String,
    kind: String = "structure",
    surfaces: List<String> = emptyList(),
  ) = DesignGuidelineRule(id, emptyList(), kind, "info", "g", "$id?", "https://x", surfaces)

  private fun encoded(document: UiBuilderDocument): JsonObject =
    Json.encodeToJsonElement(UiBuilderDocument.serializer(), document).jsonObject

  private fun document(
    systemId: String,
    root: String,
    width: Int = 192,
    height: Int = 192,
  ): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "d", "title": "D", "revision": 1,
        "catalogPin": {"systemId": "$systemId"},
        "environment": {"widthDp": $width, "heightDp": $height},
        "stateVariables": {},
        "roots": ["root"],
        "nodes": {"root": {"id": "root", "componentId": "$root"}}
      }
      """,
    )
}
