package ee.schimke.composeai.uibuilder.guidelines

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Which model actually answered a guidelines check, as OpenRouter reports it. The model a host asks
 * for may be a router (`typesafe/jev-router` picks a general model per request), so the model that
 * wrote the verdicts is recorded beside it rather than assumed.
 */
@Serializable
data class DesignGuidelineServed(
  /** The response body's `model`: the model that wrote the verdicts. */
  val model: String? = null,
  val provider: String? = null,
  /** The response's `usage.cost`, in US dollars. */
  val costUsd: Double? = null,
  /** The response `id`, which OpenRouter's generation stats are looked up by. */
  val generationId: String? = null,
  val routing: DesignGuidelineRouting? = null,
)

/**
 * How a router chose the model, from `openrouter_metadata.pipeline[name == "jev-router"].data`,
 * which OpenRouter returns when the request sends [OPENROUTER_METADATA_HEADER].
 */
@Serializable
data class DesignGuidelineRouting(
  val router: String,
  val version: String? = null,
  /** `initial`, or `continuation` when the router kept the model of an earlier, similar request. */
  val reason: String? = null,
  /** The probability the router gave the model it served. */
  val probability: Double? = null,
  /** The router's own scores for the prompt, e.g. `big_model_gain`, `visual_quality`. */
  val scores: Map<String, Double> = emptyMap(),
)

/** The request header that asks OpenRouter for routing metadata in the response body. */
const val OPENROUTER_METADATA_HEADER: String = "X-OpenRouter-Metadata"

/** Reads [DesignGuidelineServed] from a chat completion response body; empty when unreadable. */
fun DesignGuidelinePrompt.parseServed(body: String): DesignGuidelineServed {
  val root =
    runCatching { GUIDELINE_JSON.parseToJsonElement(body).jsonObject }.getOrNull()
      ?: return DesignGuidelineServed()
  fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
  val served = root.text("model")
  val routing =
    ((root["openrouter_metadata"] as? JsonObject)?.get("pipeline") as? JsonArray)
      ?.mapNotNull { it as? JsonObject }
      ?.firstOrNull { it.text("name") == "jev-router" }
      ?.let { stage ->
        val data = stage["data"] as? JsonObject ?: return@let null
        val probability =
          (data["selection_probabilities"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            // Candidates carry a dated id (`…-flash-20260910`); the served `model` does not.
            ?.firstOrNull { candidate ->
              val id = candidate.text("model")
              served != null && id != null && (id == served || id.startsWith("$served-"))
            }
            ?.let { (it["probability"] as? JsonPrimitive)?.doubleOrNull }
        val scores =
          (data["answers"] as? JsonObject)
            ?.mapNotNull { (key, value) ->
              val answer = value as? JsonObject ?: return@mapNotNull null
              (answer["noul"] as? JsonPrimitive)?.doubleOrNull?.let { key to it }
            }
            ?.toMap()
            .orEmpty()
        DesignGuidelineRouting(
          router = "typesafe/jev-router",
          version = data.text("version"),
          reason = data.text("reason"),
          probability = probability,
          scores = scores,
        )
      }
  return DesignGuidelineServed(
    model = served,
    provider = root.text("provider"),
    costUsd = ((root["usage"] as? JsonObject)?.get("cost") as? JsonPrimitive)?.doubleOrNull,
    generationId = root.text("id"),
    routing = routing,
  )
}

/** One line saying who answered, for a person reading the result. */
fun DesignGuidelineServed.describe(asked: String): String = buildString {
  append("checked by ").append(model ?: asked)
  provider?.let { append(" on ").append(it) }
  costUsd?.let { append(" · $").append(((it * 10000).toLong() / 10000.0).toString()) }
  routing?.let { r ->
    append(" · routed by ").append(r.router)
    r.reason?.let { append(" (").append(it) }
    r.probability?.let {
      append(if (r.reason == null) " (" else ", ")
        .append("p=")
        .append(((it * 100).toLong() / 100.0).toString())
    }
    if (r.reason != null || r.probability != null) append(")")
  }
  if (model != null && model != asked && routing == null)
    append(" (asked for ").append(asked).append(")")
}
