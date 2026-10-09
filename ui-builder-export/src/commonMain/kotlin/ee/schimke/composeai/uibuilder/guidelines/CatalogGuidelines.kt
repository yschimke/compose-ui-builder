package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.hostSpec
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * A catalog's own design guidance: the rules a design built from it is checked against and the
 * pictures the model is shown, published by the catalog beside its `ui-builder.policy.json` as
 * `ui-builder.guidelines.json`.
 *
 * compose-ui-builder knows how to ask a rule and how to draw a frame; it does not know which rules
 * or frames any catalog wants. A catalog that publishes no file has no guidelines check.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CatalogGuidelines(
  @EncodeDefault val schema: String = SCHEMA,
  /** The catalog system id these rules are for, as a design's `catalogPin.systemId` names it. */
  val catalog: String,
  /** The platform word the catalog declares in its policy (`wear`, `mobile`, …), for the prompt. */
  val platform: String,
  /** Bumped by the catalog whenever a rule or frame changes; recorded with every result. */
  val version: Int,
  val about: String = "",
  val frames: List<CatalogGuidelineFrame> = emptyList(),
  val rules: List<DesignGuidelineRule> = emptyList(),
) {
  /** The rules that apply to a design of [surface] targeting Remote Compose [profile]. */
  fun rulesFor(surface: String, profile: String? = null): List<DesignGuidelineRule> = rules.filter {
    it.appliesTo(surface) && it.appliesToProfile(profile)
  }

  /** As a rule set, for the prompt's provenance and the shapes that predate catalog rules. */
  fun asRuleSet(): DesignGuidelineRuleSet =
    DesignGuidelineRuleSet(schema = schema, version = version, about = about, rules = rules)

  companion object {
    const val SCHEMA: String = "compose-ui-builder/catalog-guidelines/v1"

    /** The file name a catalog publishes them under, beside `ui-builder.json`. */
    const val FILE_NAME: String = "ui-builder.guidelines.json"

    fun parse(text: String): CatalogGuidelines = GUIDELINE_JSON.decodeFromString(serializer(), text)
  }
}

/**
 * One picture a catalog asks for. [kind] says how it is drawn:
 * - `device`: the design as authored, its first frame.
 * - `unrolled`: the design [heightFactor] times as tall, so a scrolling list reaches its end.
 * - `sized`: the design at [widthDp] × [heightDp], whatever size it was authored at.
 * - `widget-host`: a Wear widget in the launcher container [hostShape] (`round`, `squircle`,
 *   `rectangular`), at the widget's own size.
 *
 * [surface] limits it to `screen` or `widget` designs; null asks it for both. [label] names it in
 * the prompt ("tablet", "Samsung"); [whenScrolls] asks for it only when the design scrolls.
 */
@Serializable
data class CatalogGuidelineFrame(
  val kind: String,
  val surface: String? = null,
  val label: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val heightFactor: Int? = null,
  val hostShape: String? = null,
  val whenScrolls: Boolean = false,
  /** What the picture shows, for the prompt. Keep it neutral: say what it is, not what is wrong. */
  val description: String? = null,
) {
  companion object {
    const val DEVICE: String = "device"
    const val UNROLLED: String = "unrolled"
    const val SIZED: String = "sized"
    const val WIDGET_HOST: String = "widget-host"
  }
}

/**
 * The frames [guidelines] asks for [document], each turned into a [DesignGuidelineFrame] the host
 * can draw. A frame the document cannot have (a widget host for a screen, an unrolled picture of a
 * design that does not scroll) is left out.
 */
fun DesignGuidelineFrames.plan(
  document: UiBuilderDocument,
  guidelines: CatalogGuidelines,
  scrolls: Boolean = false,
): List<DesignGuidelineFrame> {
  val surface = DesignGuidelinePrompt.surfaceOf(document)
  val width = document.environmentInt("widthDp")
  val height = document.environmentInt("heightDp")
  return guidelines.frames
    .filter { it.surface == null || it.surface == surface }
    .filter { !it.whenScrolls || scrolls }
    .mapNotNull { frame ->
      when (frame.kind) {
        CatalogGuidelineFrame.DEVICE ->
          DesignGuidelineFrame(
            DesignGuidelinePicture.DEVICE,
            width,
            height,
            emptyMap(),
            frame.description,
          )
        CatalogGuidelineFrame.UNROLLED -> {
          val tall = height * (frame.heightFactor ?: UNROLLED_HEIGHT_FACTOR)
          DesignGuidelineFrame(
            DesignGuidelinePicture.UNROLLED,
            width,
            tall,
            sizeOverrides(width, tall),
            frame.description,
          )
        }
        // The picture's kind is the catalog's label ("phone", "tablet", "2x1"), which is also what
        // the host caches it under.
        CatalogGuidelineFrame.SIZED -> {
          val w = frame.widthDp ?: return@mapNotNull null
          val h = frame.heightDp ?: return@mapNotNull null
          DesignGuidelineFrame(
            frame.label ?: "${w}x$h",
            w,
            h,
            sizeOverrides(w, h),
            frame.description,
          )
        }
        CatalogGuidelineFrame.WIDGET_HOST -> {
          val size = DesignGuidelineFrames.widgetSize(document) ?: return@mapNotNull null
          val shape =
            WearWidgetHostShape.entries.firstOrNull { it.id == frame.hostShape }
              ?: return@mapNotNull null
          val spec = size.hostSpec(shape)
          DesignGuidelineFrame(
            hostKind(shape),
            spec.frameWidthDp,
            spec.frameHeightDp,
            sizeOverrides(spec.frameWidthDp, spec.frameHeightDp) +
              (WearWidgetHostShape.ENVIRONMENT_KEY to JsonPrimitive(shape.id)),
            frame.description,
          )
        }
        else -> null
      }
    }
}

/** The picture kinds a host shape has always been asked as, which hosts cache and reuse by. */
private fun hostKind(shape: WearWidgetHostShape): String =
  when (shape) {
    WearWidgetHostShape.Round -> DesignGuidelinePicture.WIDGET_SAMSUNG
    WearWidgetHostShape.Squircle -> DesignGuidelinePicture.WIDGET_PIXEL_WATCH
    WearWidgetHostShape.Rectangular -> "widget-${shape.id}"
  }

private fun sizeOverrides(widthDp: Int, heightDp: Int) =
  mapOf("widthDp" to JsonPrimitive(widthDp), "heightDp" to JsonPrimitive(heightDp))

private fun UiBuilderDocument.environmentInt(name: String): Int =
  (environment[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

private const val UNROLLED_HEIGHT_FACTOR = 4
