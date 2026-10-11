package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which renderer is allowed to claim it is showing you the design, declared by the catalog.
 *
 * The Wasm canvas links Material 3 directly for `m3-catalog`, but can never link Wear's Android
 * AAR, so a `wear-m3` canvas is an approximation; the native lane (generated Kotlin on the
 * Android/Robolectric daemon) is the honest picture
 * ([`docs/design/UI_BUILDER_WEAR_SCREEN.md`](../../../../../../../docs/design/UI_BUILDER_WEAR_SCREEN.md)).
 * Declaring it once lets the editor and server agree.
 *
 * Lives in `statusSemantics` because the wire type is published elsewhere and cannot grow a field.
 * A catalog that says nothing gets [Fidelity.AUTHORITATIVE] on the canvas and the desktop daemon
 * natively.
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
     * It draws, with stand-ins for components this renderer cannot link: fine for authoring, not a
     * claim about appearance (the Wasm canvas on `wear-m3`).
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
     * Read the declaration out of a catalog's `statusSemantics`, falling back to [DEFAULT] per
     * member rather than throwing. An unknown fidelity word reads as [Fidelity.APPROXIMATE], not
     * authoritative.
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
