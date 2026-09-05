package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which renderer is allowed to claim it is showing you the design, declared by the catalog.
 *
 * ## Why a catalog gets to say this at all
 *
 * The builder has two renderers and they are not interchangeable. The editor's canvas is Compose
 * Multiplatform for Wasm, and for `m3-catalog` it draws the same Material 3 the export names — the
 * canvas *is* the answer. For `wear-m3` it cannot be, and not for want of work:
 * `androidx.wear.compose:compose-material3` is an Android AAR, which a Wasm build can never link,
 * so every Wear component on that canvas is a Material 3 lookalike standing where a Wear one goes
 * ([`docs/design/UI_BUILDER_WEAR_SCREEN.md`](../../../../../../../docs/design/UI_BUILDER_WEAR_SCREEN.md)).
 * The honest picture comes from the **native lane**, which compiles the design's generated Kotlin
 * against a real Wear classpath and renders it on the Android/Robolectric daemon.
 *
 * Left implicit, that difference has to be re-learned by every surface: the editor offers a
 * "Preview" that is a lookalike, the server picks a daemon by guessing from a catalog id, and the
 * two can disagree about the same design. Declared once in the catalog's own `statusSemantics`,
 * there is one fact and both read it.
 *
 * ## Where it lives, and why not in a field of its own
 *
 * `CatalogCapabilityV1.statusSemantics` is an open `JsonObject` whose whole job is the catalog
 * explaining its own status vocabulary — `adapterStatus`, `svgStatus`, and now this. A typed field
 * would be better and is not available: the wire type is published from compose-preview-contracts,
 * and adding a member to it is a change in a repository this one cannot make.
 *
 * ## The default is the old behaviour
 *
 * A catalog that says nothing gets [Fidelity.AUTHORITATIVE] on the canvas and the desktop daemon
 * natively, which is exactly what every catalog had before this existed. Only `wear-m3` says
 * otherwise, and it says so because it is true rather than to opt into a feature.
 */
data class UiBuilderPreviewSurfaces(
  val wasm: SurfaceClaim = SurfaceClaim(Fidelity.AUTHORITATIVE),
  val native: SurfaceClaim = SurfaceClaim(Fidelity.AUTHORITATIVE, backend = BACKEND_DESKTOP),
) {

  /** What one renderer's picture of a design is worth, and why. */
  data class SurfaceClaim(
    val fidelity: Fidelity,
    /**
     * Said in the editor where the surface is refused, so the refusal is an explanation rather than
     * a disabled control. Empty when there is nothing to add to the fidelity itself.
     */
    val reason: String = "",
    /**
     * For the native surface: which daemon compiles and draws it — `desktop` (Skiko) or `android`
     * (Robolectric). Meaningless on the Wasm surface, which has one renderer by definition.
     */
    val backend: String = "",
  )

  /** How much of the truth a surface tells about a design. */
  enum class Fidelity {
    /** What you see is what the generated code renders. */
    AUTHORITATIVE,

    /**
     * It draws, and the shapes are stand-ins for components this renderer cannot link.
     *
     * Useful for *authoring* — you can select a node, drag it, see the layout — and worth nothing
     * as a claim about how the screen looks. This is the Wasm canvas on `wear-m3`.
     */
    APPROXIMATE,

    /** The surface cannot draw this catalog at all. */
    NONE;

    /** Whether a "this is your screen" claim may be made from this surface. */
    val isAuthoritative: Boolean
      get() = this == AUTHORITATIVE
  }

  companion object {
    const val KEY: String = "previewSurfaces"
    const val BACKEND_DESKTOP: String = "desktop"
    const val BACKEND_ANDROID: String = "android"

    /** The default claims, for a catalog whose `statusSemantics` says nothing about surfaces. */
    val DEFAULT: UiBuilderPreviewSurfaces = UiBuilderPreviewSurfaces()

    /**
     * Read the declaration out of a catalog's `statusSemantics`, falling back to [DEFAULT].
     *
     * Every miss falls back rather than throwing, per member: an unreadable or partial declaration
     * must not take a catalog's editor down, and the default it lands on is the behaviour that
     * predates the field. An unknown fidelity word is the one worth being careful about — it is
     * read as [Fidelity.APPROXIMATE] rather than as authoritative, because a catalog that went to
     * the trouble of saying something about a surface was not saying "this one is perfect".
     */
    fun from(statusSemantics: JsonObject): UiBuilderPreviewSurfaces {
      val declared = (statusSemantics[KEY] as? JsonObject) ?: return DEFAULT
      return UiBuilderPreviewSurfaces(
        wasm = declared.claim("wasm", DEFAULT.wasm),
        native = declared.claim("native", DEFAULT.native),
      )
    }

    private fun JsonObject.claim(name: String, fallback: SurfaceClaim): SurfaceClaim {
      val claim = (this[name] as? JsonObject) ?: return fallback
      val fidelity =
        claim["fidelity"]?.jsonPrimitive?.contentOrNull?.let(::fidelity) ?: fallback.fidelity
      return SurfaceClaim(
        fidelity = fidelity,
        reason = claim["reason"]?.jsonPrimitive?.contentOrNull ?: fallback.reason,
        backend = claim["backend"]?.jsonPrimitive?.contentOrNull ?: fallback.backend,
      )
    }

    private fun fidelity(word: String): Fidelity =
      when (word.lowercase()) {
        "authoritative" -> Fidelity.AUTHORITATIVE
        "none" -> Fidelity.NONE
        else -> Fidelity.APPROXIMATE
      }
  }
}

/** The surface claims of a serialized catalog, read from its `statusSemantics`. */
fun previewSurfacesOf(statusSemantics: JsonObject): UiBuilderPreviewSurfaces =
  UiBuilderPreviewSurfaces.from(statusSemantics)
