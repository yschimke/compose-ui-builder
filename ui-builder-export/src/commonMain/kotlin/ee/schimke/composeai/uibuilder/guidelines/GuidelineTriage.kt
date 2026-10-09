package ee.schimke.composeai.uibuilder.guidelines

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Evidence a host can gather beyond the first pass, which a cheap decision model is asked about
 * before anything is drawn: a check sends the pictures that are always worth it (80% of the
 * signal), and draws a dark-theme or large-font picture, or attaches the accessibility tree, only
 * for the designs where it would change an answer.
 *
 * [key] is the decision's name; [question] is asked as a yes/no with a probability; [settings]
 * describe the extra render, empty for evidence that is not a picture.
 */
data class GuidelineEvidenceOffer(
  val key: String,
  val kind: String,
  val question: String,
  val settings: Map<String, String> = emptyMap(),
) {
  companion object {
    val DARK_THEME =
      GuidelineEvidenceOffer(
        "dark_theme",
        "render",
        "Would a picture of this design in the dark theme be needed to judge any of the rules — " +
          "for example colour, contrast or a background that only shows in dark?",
        mapOf("theme" to "dark"),
      )
    val LARGE_FONT =
      GuidelineEvidenceOffer(
        "large_font",
        "render",
        "Would a picture of this design at a large font scale be needed to judge any of the " +
          "rules — for example text that could truncate, overlap or clip when it grows?",
        mapOf("fontScale" to "1.5"),
      )
    val A11Y_HIERARCHY =
      GuidelineEvidenceOffer(
        "a11y_hierarchy",
        "a11y-hierarchy",
        "Would the accessibility tree (labels, roles, actions and tap-target bounds) be needed " +
          "to judge any of the rules, beyond what the design tree already states?",
      )

    val DEFAULTS: List<GuidelineEvidenceOffer> = listOf(DARK_THEME, LARGE_FONT, A11Y_HIERARCHY)
  }
}

/** Jev, TypeSafe's decision model: text only, typed answers with probabilities, no free text. */
const val JEV_DECISION_MODEL: String = "typesafe/jev-1.13"

/** OpenRouter's decisions endpoint, which Jev answers on (it is not a chat model). */
const val OPENROUTER_DECISIONS_URL: String = "https://openrouter.ai/api/alpha/decisions"

/**
 * The decisions request asking [model] which of [offers] would help judge [request]'s rules. Jev
 * reads text only, so it is given the user message (design tree, source and rule list) — about 0.3s
 * and a few hundredths of a cent.
 */
fun DesignGuidelinePrompt.triageBody(
  request: DesignGuidelineRequest,
  offers: List<GuidelineEvidenceOffer> = GuidelineEvidenceOffer.DEFAULTS,
  model: String = JEV_DECISION_MODEL,
): JsonObject = buildJsonObject {
  put("model", model)
  putJsonObject("state") {
    put(
      "task",
      "Decide which extra evidence would help a reviewer judge this UI design against the " +
        "listed guidelines. The reviewer already has the design tree, the listed pictures and " +
        "any source shown.",
    )
    // Jev's prompt is capped well below a chat model's; the tree and the rules come first.
    put("design", request.userText.take(MAX_TRIAGE_CHARS))
  }
  putJsonObject("questions") {
    offers.forEach { offer ->
      putJsonObject(offer.key) {
        put("type", "noul")
        put("instructions", offer.question)
      }
    }
  }
}

/**
 * The probability Jev gave each offer, by key, from a decisions response body; offers it did not
 * answer are left out, so a host treats them as "not needed" rather than guessing.
 */
fun DesignGuidelinePrompt.parseTriage(body: String): Map<String, Double> = runCatching {
  val answers =
    GUIDELINE_JSON.parseToJsonElement(body).jsonObject["answers"] as? JsonObject
      ?: return@runCatching emptyMap()
  answers
    .mapNotNull { (key, value) ->
      ((value as? JsonObject)?.get("noul") as? JsonPrimitive)?.doubleOrNull?.let { key to it }
    }
    .toMap()
}
  .getOrDefault(emptyMap())

/** The offers worth gathering: Jev more likely than not says they would help. */
fun List<GuidelineEvidenceOffer>.wanted(
  probabilities: Map<String, Double>,
  threshold: Double = 0.5,
): List<GuidelineEvidenceOffer> = filter { (probabilities[it.key] ?: 0.0) >= threshold }

/** About 24k tokens: under Jev's 32k prompt cap with room for the questions. */
private const val MAX_TRIAGE_CHARS = 96_000
