package ee.schimke.composeai.uibuilder.guidelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class GuidelineServedTest {
  private fun resource(name: String) =
    checkNotNull(javaClass.getResource("/guidelines/$name")).readText()

  @Test
  fun `a routed check records the model that answered and why it was chosen`() {
    val served = DesignGuidelinePrompt.parseServed(resource("router-completion.json"))
    assertEquals("deepseek/deepseek-v4.1-flash", served.model)
    assertEquals("CoreWeave", served.provider)
    assertTrue((served.costUsd ?: 0.0) > 0.0)
    assertTrue(served.generationId!!.startsWith("gen-"))
    val routing = served.routing!!
    assertEquals("typesafe/jev-router", routing.router)
    assertEquals("initial", routing.reason)
    assertEquals(0.895, routing.probability!!, 0.001)
    assertTrue("visual_quality" in routing.scores, routing.scores.toString())
    assertTrue(
      served
        .describe("typesafe/jev-router")
        .startsWith("checked by deepseek/deepseek-v4.1-flash on CoreWeave")
    )
  }

  @Test
  fun `a direct check has no routing, and an unreadable body records nothing`() {
    val served =
      DesignGuidelinePrompt.parseServed(
        """{"id":"gen-1","model":"deepseek/deepseek-v4.1-flash","provider":"DeepInfra",""" +
          """"usage":{"cost":0.0027},"choices":[]}"""
      )
    assertNull(served.routing)
    assertEquals(
      "checked by deepseek/deepseek-v4.1-flash on DeepInfra · $0.0027",
      served.describe("deepseek/deepseek-v4.1-flash"),
    )
    assertEquals(DesignGuidelineServed(), DesignGuidelinePrompt.parseServed("not json"))
  }

  @Test
  fun `Jev is asked which extra evidence would help, and only what it wants is gathered`() {
    val request =
      DesignGuidelineRequest(
        revision = 1,
        platform = "wear",
        rules = DesignGuidelineRequestRules(1, "s", 0, emptyList()),
        systemPrompt = "",
        userText = "Design tree: …",
        responseSchema = DesignGuidelinePrompt.responseSchema,
      )
    val body = DesignGuidelinePrompt.triageBody(request)
    assertEquals(JEV_DECISION_MODEL, body["model"].toString().trim('"'))
    val questions = body["questions"]!!.jsonObject
    assertEquals(setOf("dark_theme", "large_font", "a11y_hierarchy"), questions.keys)

    val probabilities = DesignGuidelinePrompt.parseTriage(resource("jev-decisions.json"))
    assertEquals(0.71, probabilities.getValue("dark_theme"), 0.0001)
    assertEquals(
      listOf("dark_theme", "a11y_hierarchy"),
      GuidelineEvidenceOffer.DEFAULTS.wanted(probabilities).map { it.key },
    )
    assertEquals(emptyMap(), DesignGuidelinePrompt.parseTriage("{}"))
  }
}
