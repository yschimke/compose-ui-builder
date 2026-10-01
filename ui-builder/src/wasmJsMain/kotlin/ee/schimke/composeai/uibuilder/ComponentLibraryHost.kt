package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorLibraryComponent
import ee.schimke.composeai.uibuilder.editor.EditorLibrarySymbol
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The project's shared component library, as the palette reads it.
 *
 * `GET /api/ui-builder/v1/component-library` lists what the projects this host serves publish, and
 * `…/{system}/{componentId}` answers one symbol with its body and digest; both authenticate like
 * every other builder call. A host that serves no library answers an error, and the palette then
 * simply has no shelf for it — the reason [loadComponentDrift] tolerates failure too.
 */
internal suspend fun loadComponentLibrary(): List<EditorLibraryComponent> =
  try {
    val listing = libraryJson.parseToJsonElement(fetchText(COMPONENT_LIBRARY_PATH)).jsonObject
    listing["components"]?.jsonArray.orEmpty().mapNotNull { row ->
      val entry = row as? JsonObject ?: return@mapNotNull null
      fun field(name: String) = entry[name]?.jsonPrimitive?.contentOrNull
      EditorLibraryComponent(
        system = field("system") ?: return@mapNotNull null,
        componentId = field("componentId") ?: return@mapNotNull null,
        paletteId = field("paletteId") ?: return@mapNotNull null,
        title = field("title") ?: field("componentId") ?: return@mapNotNull null,
        description = field("description"),
      )
    }
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    emptyList()
  }

/** One published symbol, fetched when its tile is pressed; null when it cannot be read. */
internal suspend fun loadLibrarySymbol(component: EditorLibraryComponent): EditorLibrarySymbol? =
  try {
    val body =
      libraryJson
        .parseToJsonElement(
          fetchText(
            "$COMPONENT_LIBRARY_PATH/${encodeUrlComponent(component.system)}/" +
              encodeUrlComponent(component.componentId)
          )
        )
        .jsonObject
    val nodes =
      body["nodes"]?.jsonObject.orEmpty().mapValues { (_, node) ->
        libraryJson.decodeFromJsonElement<UiBuilderNode>(node)
      }
    EditorLibrarySymbol(
      component = component,
      digest = body["digest"]?.jsonPrimitive?.contentOrNull ?: return null,
      declaration = body["component"] as? JsonObject ?: return null,
      nodes = nodes,
    )
  } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    null
  }

private const val COMPONENT_LIBRARY_PATH = "/api/ui-builder/v1/component-library"

private val libraryJson = Json { ignoreUnknownKeys = true }
