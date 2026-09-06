package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which kind of screen a builder catalog authors for, declared by the catalog.
 *
 * ## Why a catalog says this
 *
 * The builder started with one catalog and a phone screen, and every catalog since has been a
 * different *platform* rather than a different flavour of the same one: `wear-m3` authors a round
 * watch screen against Wear Compose, `remote-m3` a Wear widget body that is a Remote Compose
 * document rather than a composition. Nothing said so in data. The New design chooser listed the
 * three side by side as though they were interchangeable, and there was no way to ask the question
 * that [UiBuilderComponentPacks] needs answered — *which other component sets are compatible with
 * this design at all?* — because "compatible" is a statement about the platform, not the catalog.
 *
 * A Confetti mobile component drops into a Material 3 phone screen and compiles against the same
 * foundation; it does not drop into a Wear widget, whose body is `@RemoteComposable` and cannot
 * call it. The platform is the fact that decides that, so the catalog declares it once and both the
 * chooser and the pack merge read it.
 *
 * ## Where it lives
 *
 * `CatalogCapabilityV1.statusSemantics`, under [KEY], for the reason [UiBuilderPreviewSurfaces]
 * gives: the wire type is published from compose-preview-contracts and cannot grow a field from
 * here. A catalog that says nothing is [MOBILE], which is what every catalog was before there was
 * anything else to be.
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
  REMOTE_COMPOSE("remote-compose", "Remote Compose");

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
