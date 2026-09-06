package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Why a root `m3/surface`'s `containerColor` is not the frame's ground, when it is not.
 *
 * The frame's ground — the pixels no node paints — comes from `environment.theme`, in every
 * renderer: the canvas draws it in the theme's `background`, and a native render composites on the
 * preview's own backdrop. A root surface paints its `containerColor` across the area it is measured
 * to, and nothing more. So a root that fills the frame *is* the ground, and a root that does not —
 * a surface with no size modifier wraps its content, exactly as `Surface` does — paints a patch
 * behind its content and leaves the rest to the theme.
 *
 * That second case is the one an author reads as "the property does nothing"
 * (compose-preview-server #485): the root is the node they reach first, its `containerColor` is
 * declared in the catalog like any other, the reducer commits it, and the picture keeps the theme's
 * ground. There is no error to search for, which is why this names it: on the export's diagnostics,
 * where an agent looks, and in the editor's problems panel, where a person does.
 */
object RootSurfaceGround {
  const val CODE: String = "ROOT_SURFACE_DOES_NOT_FILL_FRAME"

  /** The notice for one root, or null when every root surface with a colour fills the frame. */
  data class Notice(val nodeId: String, val message: String)

  fun diagnose(document: UiBuilderDocument): Notice? =
    document.roots
      .asSequence()
      .mapNotNull(document.nodes::get)
      .firstOrNull { it.componentId == SURFACE && it.declaresContainerColor() && !it.fillsFrame() }
      ?.let { root ->
        Notice(
          nodeId = root.id,
          message =
            "root `$SURFACE` `${root.id}` sets `containerColor` but does not fill the frame, so " +
              "it paints only behind its own content and the frame's ground around it comes " +
              "from `environment.theme`; add a `fillMaxSize` modifier to `${root.id}` to make " +
              "its `containerColor` the ground, or set `environment.theme` to the ground you want",
        )
      }

  private fun UiBuilderNode.declaresContainerColor(): Boolean =
    (properties[CONTAINER_COLOR] as? JsonObject)
      ?.get("value")
      ?.jsonPrimitive
      ?.contentOrNull
      .orEmpty()
      .isNotEmpty()

  /**
   * Whether the modifiers ask for the whole frame. Explicit dp sizes are deliberately not counted:
   * a root sized to a number covers the frame only by coincidence, and the notice is still true of
   * it.
   */
  private fun UiBuilderNode.fillsFrame(): Boolean {
    val types = modifiers.mapNotNull {
      (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    }
    return "fillMaxSize" in types ||
      "matchParentSize" in types ||
      ("fillMaxWidth" in types && "fillMaxHeight" in types)
  }

  private const val SURFACE = "m3/surface"
  private const val CONTAINER_COLOR = "containerColor"
}
