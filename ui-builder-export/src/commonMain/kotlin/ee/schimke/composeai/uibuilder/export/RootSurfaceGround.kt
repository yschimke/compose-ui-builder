package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Why a root `m3/surface`'s `containerColor` is not the frame's ground, when it is not.
 *
 * The frame's ground comes from `environment.theme` in every renderer; a root surface only paints
 * the area it is measured to. A root without a filling size modifier therefore paints a patch, and
 * an author reads the property as doing nothing. This names that on the export's diagnostics and in
 * the editor's problems panel.
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

  /** Whether the modifiers ask for the whole frame; explicit dp sizes do not count. */
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
