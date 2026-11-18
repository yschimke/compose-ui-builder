package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.runtime.compositionLocalOf

/**
 * The placeholder pictures a complication's image sources draw on the canvas: drawn in code, not
 * loaded, so the preview needs no resources. [glyph] picks the picture.
 */
internal enum class WffSampleImage(val glyph: String) {
  BATTERY("battery"),
  STEPS("steps"),
  HEART("heart"),
  SUN("sun"),
  CALENDAR("calendar"),
  BELL("bell"),
  CLOCK("clock"),
  /** A photo: a landscape, for `PHOTO_IMAGE`. */
  PHOTO("photo"),
}

/**
 * What a complication slot shows on the canvas, as the data a provider sends for one type: its
 * `[COMPLICATION.TEXT]` and `TITLE`, the `RANGED_VALUE_*` and `GOAL_PROGRESS_*` numbers, the
 * weighted elements and the image sources. Not what a watch would show; enough for a face to look
 * lived in, and for arcs and progress bars to sit where a real value puts them.
 */
internal data class WffComplicationSample(
  val type: String = SHORT_TEXT,
  val text: String = "",
  val title: String = "",
  /** `RANGED_VALUE_VALUE`, between [rangedMin] and [rangedMax]; null when the type has none. */
  val rangedValue: Double? = null,
  val rangedMin: Double = 0.0,
  val rangedMax: Double = 100.0,
  val goalValue: Double? = null,
  val goalTarget: Double? = null,
  /** `WEIGHTED_ELEMENTS`: each element's weight and ARGB colour, in order around the ring. */
  val weights: List<Pair<Double, Long>> = emptyList(),
  /** The icon: what `MONOCHROMATIC_IMAGE` and `SMALL_IMAGE` draw. */
  val image: WffSampleImage? = null,
) {
  /** The one line a slot with nothing of its own draws. */
  val line: String
    get() = if (title.isEmpty()) text else "$text $title"

  /** How far along its range the value is, 0 to 1: a ranged value, else a goal's progress. */
  val progress: Double?
    get() =
      rangedValue?.let { value ->
        if (rangedMax <= rangedMin) 0.0
        else ((value - rangedMin) / (rangedMax - rangedMin)).coerceIn(0.0, 1.0)
      }
        ?: goalValue?.let { value ->
          goalTarget?.takeIf { it > 0 }?.let { (value / it).coerceIn(0.0, 1.0) }
        }

  /** This sample with its ranged (or goal) value moved to [value], as the picker's slider sets. */
  fun withValue(value: Double): WffComplicationSample =
    when {
      rangedValue != null ->
        copy(rangedValue = value.coerceIn(rangedMin, rangedMax), text = valueText(value, text))
      goalValue != null -> copy(goalValue = value.coerceAtLeast(0.0), text = valueText(value, text))
      else -> this
    }

  companion object {
    const val SHORT_TEXT = "SHORT_TEXT"
    const val LONG_TEXT = "LONG_TEXT"
    const val RANGED_VALUE = "RANGED_VALUE"
    const val GOAL_PROGRESS = "GOAL_PROGRESS"
    const val WEIGHTED_ELEMENTS = "WEIGHTED_ELEMENTS"
    const val MONOCHROMATIC_IMAGE = "MONOCHROMATIC_IMAGE"
    const val SMALL_IMAGE = "SMALL_IMAGE"
    const val PHOTO_IMAGE = "PHOTO_IMAGE"
    const val NO_DATA = "NO_DATA"
    const val EMPTY = "EMPTY"

    /** Every complication type, in the order the picker offers them. */
    val TYPES: List<String> =
      listOf(
        SHORT_TEXT,
        LONG_TEXT,
        RANGED_VALUE,
        GOAL_PROGRESS,
        WEIGHTED_ELEMENTS,
        MONOCHROMATIC_IMAGE,
        SMALL_IMAGE,
        PHOTO_IMAGE,
        NO_DATA,
        EMPTY,
      )

    /** The system providers the picker offers, with a label for each. */
    val PROVIDERS: List<Pair<String, String>> =
      listOf(
        "WATCH_BATTERY" to "Battery",
        "STEP_COUNT" to "Steps",
        "HEART_RATE" to "Heart rate",
        "SUNRISE_SUNSET" to "Sunrise",
        "NEXT_EVENT" to "Next event",
        "DAY_OF_WEEK" to "Day of week",
        "DAY_AND_DATE" to "Day and date",
        "TIME_AND_DATE" to "Time and date",
        "UNREAD_NOTIFICATION_COUNT" to "Notifications",
      )

    val Default = forProvider("HEART_RATE")

    private val WEIGHTS =
      listOf(3.0 to 0xFF4FC3F7, 2.0 to 0xFF81C784, 1.0 to 0xFFFFB74D, 1.0 to 0xFFE57373)

    /**
     * What [provider] sends as [type] (`SHORT_TEXT` when blank). A type the provider has no value
     * for still gets its text, a placeholder image and a mid value; `NO_DATA` is `--` and `EMPTY`
     * is nothing at all.
     */
    fun forProvider(provider: String, type: String = ""): WffComplicationSample {
      val base: WffComplicationSample =
        when (provider) {
          "WATCH_BATTERY" ->
            WffComplicationSample(
              text = "85%",
              rangedValue = 85.0,
              rangedMin = 0.0,
              rangedMax = 100.0,
              image = WffSampleImage.BATTERY,
            )
          "STEP_COUNT" ->
            WffComplicationSample(
              text = "6,482",
              rangedValue = 6482.0,
              rangedMin = 0.0,
              rangedMax = 10000.0,
              goalValue = 6482.0,
              goalTarget = 10000.0,
              image = WffSampleImage.STEPS,
            )
          "HEART_RATE",
          "" ->
            WffComplicationSample(
              text = "72",
              title = "bpm",
              rangedValue = 72.0,
              rangedMin = 40.0,
              rangedMax = 200.0,
              image = WffSampleImage.HEART,
            )
          "SUNRISE_SUNSET" ->
            WffComplicationSample(text = "06:42", title = "Sunrise", image = WffSampleImage.SUN)
          "NEXT_EVENT" ->
            WffComplicationSample(
              text = "10:30",
              title = "Stand-up",
              image = WffSampleImage.CALENDAR,
            )
          "DAY_OF_WEEK" -> WffComplicationSample(text = "Thu", image = WffSampleImage.CALENDAR)
          "DAY_AND_DATE" -> WffComplicationSample(text = "Thu 16", image = WffSampleImage.CALENDAR)
          "TIME_AND_DATE" -> WffComplicationSample(text = "10:08", image = WffSampleImage.CLOCK)
          "UNREAD_NOTIFICATION_COUNT" ->
            WffComplicationSample(text = "3", image = WffSampleImage.BELL)
          else -> forProvider("HEART_RATE").copy(title = "")
        }
      return base.ofType(type.ifBlank { SHORT_TEXT })
    }

    private fun valueText(value: Double, current: String): String {
      val whole = kotlin.math.round(value).toLong()
      return when {
        current.endsWith("%") -> "$whole%"
        current.contains(',') -> whole.toString().reversed().chunked(3).joinToString(",").reversed()
        else -> whole.toString()
      }
    }
  }

  /** This provider's data as [type] sends it. */
  private fun ofType(type: String): WffComplicationSample =
    when (type) {
      LONG_TEXT ->
        copy(
          type = type,
          text = if (title.isEmpty()) text else "$title $text",
          title = "",
        )
      RANGED_VALUE -> copy(type = type, rangedValue = rangedValue ?: (rangedMin + rangedMax) / 2)
      GOAL_PROGRESS ->
        copy(
          type = type,
          goalValue = goalValue ?: rangedValue ?: 50.0,
          goalTarget = goalTarget ?: rangedMax,
          rangedValue = null,
        )
      WEIGHTED_ELEMENTS -> copy(type = type, weights = WEIGHTS)
      MONOCHROMATIC_IMAGE,
      SMALL_IMAGE -> copy(type = type, image = image ?: WffSampleImage.CLOCK)
      PHOTO_IMAGE -> copy(type = type, image = WffSampleImage.PHOTO)
      NO_DATA -> WffComplicationSample(type = type, text = "--")
      EMPTY -> WffComplicationSample(type = type)
      else -> copy(type = type)
    }.let {
      // Only the types that carry a number carry one.
      if (type == RANGED_VALUE || type == GOAL_PROGRESS) it
      else it.copy(rangedValue = null, goalValue = null, goalTarget = null)
    }
}

/**
 * What the editor's complication picker chose for one slot: a provider, a type and, for a ranged or
 * goal type, a value. Editor state, drawn by the canvas and the preview, never in the document, so
 * never in the exported XML.
 */
internal data class WffComplicationPick(
  val provider: String? = null,
  val type: String? = null,
  val value: Double? = null,
)

/** The picker's choices, by complication slot node id. */
internal val LocalWffComplicationPicks = compositionLocalOf {
  emptyMap<String, WffComplicationPick>()
}

/**
 * The sample a slot draws: the picker's choice for [slotId] over the slot's own default provider
 * and type, limited to a type the slot supports (or `NO_DATA` and `EMPTY`, which every slot can
 * show).
 */
internal fun wffSlotSample(
  slotId: String,
  defaultProvider: String,
  defaultType: String,
  supportedTypes: List<String>,
  picks: Map<String, WffComplicationPick>,
): WffComplicationSample {
  val pick = picks[slotId]
  val allowed = supportedTypes + WffComplicationSample.NO_DATA + WffComplicationSample.EMPTY
  val type =
    pick?.type?.takeIf { it in allowed }
      ?: defaultType.takeIf { it.isNotEmpty() }
      ?: supportedTypes.firstOrNull()
      ?: WffComplicationSample.SHORT_TEXT
  val sample = WffComplicationSample.forProvider(pick?.provider ?: defaultProvider, type)
  return pick?.value?.let(sample::withValue) ?: sample
}
