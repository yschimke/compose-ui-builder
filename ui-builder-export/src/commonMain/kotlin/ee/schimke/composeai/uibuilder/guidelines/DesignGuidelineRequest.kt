package ee.schimke.composeai.uibuilder.guidelines

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Everything a guidelines model is asked about one design revision, before a model is chosen: the
 * exact text it reads, the pictures it sees, the rules it answers and where each part came from.
 *
 * It is a value people and agents can read, not only something sent: the editor shows it under
 * **Prompt**, compose-preview-server returns the same shape from `ui_builder_guidelines_prompt` and
 * `GET …/designs/{id}/guidelines/prompt`, and an agent can hand it to its own model instead of
 * spending anybody's OpenRouter key. [DesignGuidelinePrompt.body] turns it into the request.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DesignGuidelineRequest(
  @EncodeDefault val schema: String = SCHEMA,
  val designId: String? = null,
  val revision: Int,
  /** `wear` or `glasses`; null for a catalog with no guidelines written for it. */
  val platform: String?,
  val rules: DesignGuidelineRequestRules,
  /** The pictures attached, in the order the user message describes them. */
  val pictures: List<DesignGuidelinePicture> = emptyList(),
  /** Whether the generated Compose source is part of [userText]. */
  val sourceAttached: Boolean = false,
  val systemPrompt: String,
  val userText: String,
  /** The JSON schema the reply is held to. */
  val responseSchema: JsonObject,
  /** Where each part of the prompt comes from, one sentence each, for a person reading it. */
  val provenance: List<String> = emptyList(),
) {
  companion object {
    const val SCHEMA: String = "compose-ui-builder/guidelines-prompt/v1"
  }
}

@Serializable
data class DesignGuidelineRequestRules(
  /** The rule set's `version`. */
  val version: Int,
  /** The rule set's home, which every rule's own `source` points past. */
  val source: String,
  /** How many rules the set holds for this platform. */
  val forPlatform: Int,
  /** The rules this request asks about, with their guidance and source. */
  val asked: List<DesignGuidelineRule>,
  /** Visual rules left out because no picture could be attached. */
  val visualSkipped: Int = 0,
)

/**
 * A picture attached to a guidelines request: one [DesignGuidelineFrame] drawn. [kind] says which
 * frame (see the constants), and [description] is how the user message introduces it. [dataUrl] is
 * absent where the bytes travel separately, as MCP image blocks do.
 */
@Serializable
data class DesignGuidelinePicture(
  val kind: String,
  val description: String,
  val widthDp: Int,
  val heightDp: Int,
  val dataUrl: String? = null,
) {
  companion object {
    /** The first frame on the design's own device. */
    const val DEVICE: String = "device"

    /** A scrolling Wear screen on a canvas tall enough for its whole list. */
    const val UNROLLED: String = "unrolled"

    /** A phone or tablet design in a compact window. */
    const val PHONE: String = "phone"

    /** A phone or tablet design in an expanded window. */
    const val TABLET: String = "tablet"

    /** A Wear widget in Samsung's stadium-shaped launcher container. */
    const val WIDGET_SAMSUNG: String = "widget-samsung"

    /** A Wear widget in the Pixel Watch's rounded-rectangle launcher container. */
    const val WIDGET_PIXEL_WATCH: String = "widget-pixel-watch"

    /** [frame] drawn as [dataUrl], as picture [index] of its request. */
    fun of(frame: DesignGuidelineFrame, index: Int, dataUrl: String?) =
      DesignGuidelinePicture(
        frame.kind,
        frame.describe(index),
        frame.widthDp,
        frame.heightDp,
        dataUrl,
      )

    fun device(widthDp: Int, heightDp: Int, dataUrl: String?, index: Int = 1) =
      of(DesignGuidelineFrame(DEVICE, widthDp, heightDp, emptyMap()), index, dataUrl)
  }
}

/** The rule set's home, linked from the Prompt view. */
const val GUIDELINE_RULES_URL: String =
  "https://github.com/yschimke/compose-ui-builder/blob/main/docs/guidelines/android-design-guidelines.json"

/** Turns [request] into the OpenRouter chat-completions body for [model]. */
fun DesignGuidelinePrompt.body(request: DesignGuidelineRequest, model: String): JsonObject =
  buildJsonObject {
    put("model", model)
    put("temperature", 0)
    putJsonArray("messages") {
      add(
        buildJsonObject {
          put("role", "system")
          put("content", request.systemPrompt)
        }
      )
      add(
        buildJsonObject {
          put("role", "user")
          putJsonArray("content") {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", request.userText)
              }
            )
            request.pictures.forEach { picture ->
              val url = picture.dataUrl ?: return@forEach
              add(
                buildJsonObject {
                  put("type", "image_url")
                  putJsonObject("image_url") { put("url", url) }
                }
              )
            }
          }
        }
      )
    }
    putJsonObject("response_format") {
      put("type", "json_schema")
      putJsonObject("json_schema") {
        put("name", "guideline_verdicts")
        put("strict", true)
        put("schema", request.responseSchema)
      }
    }
  }

/**
 * A request built in this editor, for a host with no server to build one: the design tree and, when
 * the host has them, the device picture and the generated source. compose-preview-server builds the
 * same shape with native renders; prefer that one when it is reachable.
 */
fun DesignGuidelinePrompt.prepare(
  rules: DesignGuidelineRuleSet,
  designId: String?,
  revision: Int,
  document: JsonObject,
  devicePicture: String?,
  source: String?,
): DesignGuidelineRequest {
  val systemId =
    (document["catalogPin"] as? JsonObject)?.get("systemId")?.let {
      (it as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
  val platform = systemId?.let(::platformOf)
  val environment = document["environment"] as? JsonObject
  fun dim(name: String) =
    (environment?.get(name) as? kotlinx.serialization.json.JsonPrimitive)
      ?.content
      ?.toDoubleOrNull()
      ?.toInt() ?: 0
  val applicable = platform?.let(rules::forPlatform).orEmpty()
  val pictures =
    listOfNotNull(
      devicePicture?.let { DesignGuidelinePicture.device(dim("widthDp"), dim("heightDp"), it) }
    )
  val asked = if (pictures.isNotEmpty()) applicable else applicable.filterNot { it.visual }
  return DesignGuidelineRequest(
    designId = designId,
    revision = revision,
    platform = platform,
    rules =
      DesignGuidelineRequestRules(
        version = rules.version,
        source = GUIDELINE_RULES_URL,
        forPlatform = applicable.size,
        asked = asked,
        visualSkipped = applicable.size - asked.size,
      ),
    pictures = pictures,
    sourceAttached = source != null,
    systemPrompt = SYSTEM_PROMPT,
    userText =
      if (platform == null) ""
      else userText(platform, document, asked, pictures.map { it.description }, source),
    responseSchema = responseSchema,
    provenance = provenance(rules.version, applicable.size, asked.size, pictures, source != null),
  )
}

/** One sentence per part of a request, saying where it came from. */
fun DesignGuidelinePrompt.provenance(
  rulesVersion: Int,
  forPlatform: Int,
  asked: Int,
  pictures: List<DesignGuidelinePicture>,
  sourceAttached: Boolean,
): List<String> = buildList {
  add(
    "The rules are compose-ui-builder's design-guideline set (version $rulesVersion): " +
      "$forPlatform for this platform, $asked asked here. Each quotes the Android design guidance " +
      "it comes from (developer.android.com design guides: Wear OS, adaptive and large-screen " +
      "layouts, Jetpack Compose Glimmer) and links its source."
  )
  add(
    "The system prompt is fixed: it tells the model to answer each rule's yes/no question from " +
      "the evidence given, and to reply as JSON held to the response schema."
  )
  add(
    "The design tree is generated from this revision of the design: each node's id, component, " +
      "properties, modifiers and slots, without asset bytes."
  )
  add(
    if (sourceAttached)
      "The Jetpack Compose source is what this design exports to, from the same generator as Export."
    else "No Compose source is attached: the design did not export, or this host cannot export it."
  )
  when {
    pictures.isEmpty() ->
      add(
        "No picture is attached, so the visual rules are left out and the tree is all the model sees."
      )
    else ->
      pictures.forEach { picture ->
        add(
          when (picture.kind) {
            DesignGuidelinePicture.DEVICE ->
              "The device picture is a render of the design's first frame on its own device."
            DesignGuidelinePicture.UNROLLED ->
              "The unrolled picture renders the same design on a canvas tall enough for its whole " +
                "list, so the end of the list and the revealed edge button are visible."
            DesignGuidelinePicture.PHONE ->
              "The phone picture renders the design at ${picture.widthDp}×${picture.heightDp}dp, " +
                "a compact window, whatever size it was authored at."
            DesignGuidelinePicture.TABLET ->
              "The tablet picture renders the same design at " +
                "${picture.widthDp}×${picture.heightDp}dp, an expanded window, so the adaptive " +
                "rules can compare the two."
            DesignGuidelinePicture.WIDGET_SAMSUNG ->
              "The Samsung picture renders the widget in the stadium-shaped container Samsung's " +
                "launcher gives it (${picture.widthDp}×${picture.heightDp}dp)."
            DesignGuidelinePicture.WIDGET_PIXEL_WATCH ->
              "The Pixel Watch picture renders the widget in the rounded-rectangle container the " +
                "Pixel Watch launcher gives it (${picture.widthDp}×${picture.heightDp}dp)."
            else -> "A picture of the design is attached."
          }
        )
      }
  }
}

/**
 * A design's latest guidelines result as its host keeps it: the verdicts and what they answered,
 * not the findings, so every reader derives findings from the rules the same way. Written by the
 * editor after a run on a person's key, by `ui_builder_check_design`, and by an agent that judged
 * the prompt with its own model (`ui_builder_record_guidelines`). The host fills [ranBy] and
 * [recordedAtEpochMillis] from the credential, never from the body.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DesignGuidelineRecord(
  @EncodeDefault val schema: String = SCHEMA,
  val designId: String? = null,
  val revision: Int,
  val model: String,
  val rulesVersion: Int,
  /** The rule ids the request asked about. */
  val asked: List<String>,
  val verdicts: List<DesignGuidelineVerdict>,
  val ranBy: String? = null,
  val recordedAtEpochMillis: Long? = null,
) {
  companion object {
    const val SCHEMA: String = "compose-ui-builder/guidelines-result/v1"
  }
}
