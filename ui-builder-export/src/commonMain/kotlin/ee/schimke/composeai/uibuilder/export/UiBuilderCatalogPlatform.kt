package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which kind of screen a builder catalog authors for, declared by the catalog (e.g. a phone
 * screen, a round watch screen, a Remote Compose widget body). It decides compatibility: the New
 * design chooser groups by it, and [UiBuilderComponentPacks] merges packs only into catalogs of the
 * same platform.
 *
 * Stored in `statusSemantics` under [KEY], as [UiBuilderPreviewSurfaces] explains. A catalog that
 * says nothing is [MOBILE].
 */
enum class UiBuilderCatalogPlatform(
  /** The word the catalog writes. */
  val wireValue: String,
  /** The word a person reads — a section heading in the chooser, a line in the pack settings. */
  val label: String,
) {
  /** A phone or tablet screen: Jetpack Compose or Compose Multiplatform, Material 3 foundation. */
  MOBILE("mobile", "Mobile"),

  /** A round watch screen: Wear Compose, rendered on the Android daemon. */
  WEAR("wear", "Wear"),

  /** A Remote Compose document — a Wear widget body — played rather than composed. */
  REMOTE_COMPOSE("remote-compose", "Remote Compose"),

  /**
   * An A2UI surface: a protocol document an agent sends, drawn by whichever client holds the
   * catalog. Its own platform because nothing from any other one can be sent to an A2UI client.
   */
  A2UI("a2ui", "A2UI");

  companion object {
    const val KEY: String = "platform"

    /** What a catalog that says nothing is. */
    val DEFAULT: UiBuilderCatalogPlatform = MOBILE

    /** The platform a catalog declares in its `statusSemantics`, or [DEFAULT]. */
    fun from(statusSemantics: JsonObject): UiBuilderCatalogPlatform =
      statusSemantics[KEY]?.jsonPrimitive?.contentOrNull?.let(::fromWord) ?: DEFAULT

    /** The platform [word] names, or null for a word no platform answers to. */
    fun fromWord(word: String): UiBuilderCatalogPlatform? = entries.firstOrNull {
      it.wireValue.equals(word.trim(), ignoreCase = true)
    }
  }
}
