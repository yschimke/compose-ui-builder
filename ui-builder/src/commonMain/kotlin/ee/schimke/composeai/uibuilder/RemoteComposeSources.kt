package ee.schimke.composeai.uibuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one component every [RemoteComposeSource] is inserted as.
 *
 * Named because three places have to agree on it: the palette asks whether the pinned catalog
 * offers it, the reducer inserts it, and the renderer plays it.
 */
const val REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID: String = "remote-compose/document"

/**
 * One published Remote Compose document the pinned catalog offers as authoring content.
 *
 * The `remote-compose/document` component takes its child document as a Base64 `documentBase64`
 * property, and until now nothing in the editor could produce one: the inspector offered a plain
 * text field, so embedding a real component meant pasting a couple of kilobytes of Base64 by hand.
 * A source is the missing half — the catalog's own published document, named, so adding one is the
 * same gesture as adding any other component.
 *
 * It is deliberately *not* a [ee.schimke.composeai.uibuilder.capability.ComponentCapability]. The
 * `remote-m3` catalog publishes 476 stickers; declaring each of them as a component would put the
 * whole sheet through the capability wire, the validator and the exporter to describe content that
 * is always the same component with different bytes. The component stays one; the sources are a
 * list of bytes it can be given.
 *
 * @property id the preview id in the serving catalog — what `render/<id>.rc` addresses.
 * @property label the catalog's own display name for it.
 * @property group the component family, for the palette's headings.
 */
data class RemoteComposeSource(val id: String, val label: String, val group: String)

/**
 * The Remote Compose documents in a `GET /{system}/api/previews` response.
 *
 * Reads the server's `remoteCompose` flag rather than probing `render/<id>.rc` per preview: the
 * host already knows which previews carry an `ir/<id>.rc` sidecar, and a catalog of Jetpack Compose
 * previews correctly yields an empty palette instead of 476 404s.
 *
 * Tolerant of unknown keys, like every other client parse of this endpoint — the previews API grows
 * fields, and a palette that empties itself over one is worse than a palette that ignores it.
 */
fun parseRemoteComposeSources(previewsJson: String): List<RemoteComposeSource> =
  PREVIEWS_JSON.decodeFromString(PreviewsPayload.serializer(), previewsJson)
    .previews
    .filter { it.remoteCompose && it.id.isNotBlank() }
    .map {
      RemoteComposeSource(
        id = it.id,
        label = it.label.ifBlank { it.id },
        group =
          it.id
            .substringBefore(SOURCE_GROUP_SEPARATOR, missingDelimiterValue = UNGROUPED_SOURCES)
            .ifBlank { UNGROUPED_SOURCES },
      )
    }
    .sortedWith(compareBy(RemoteComposeSource::group, RemoteComposeSource::label))

/** The palette's search, over the same three strings the component list matches on. */
fun filterRemoteComposeSources(
  sources: List<RemoteComposeSource>,
  query: String,
): List<RemoteComposeSource> {
  val needle = query.trim().lowercase()
  if (needle.isEmpty()) return sources
  return sources.filter {
    it.label.lowercase().contains(needle) ||
      it.id.lowercase().contains(needle) ||
      it.group.lowercase().contains(needle)
  }
}

/**
 * `appcard__ideal__default__compact` — the catalog's own sticker-id convention, whose first segment
 * is the component family. Used for headings only; an id without one lands under
 * [UNGROUPED_SOURCES] rather than being dropped.
 */
private const val SOURCE_GROUP_SEPARATOR = "__"

private const val UNGROUPED_SOURCES = "documents"

private val PREVIEWS_JSON = Json { ignoreUnknownKeys = true }

@Serializable private data class PreviewsPayload(val previews: List<PreviewEntry> = emptyList())

@Serializable
private data class PreviewEntry(
  val id: String = "",
  val label: String = "",
  val remoteCompose: Boolean = false,
)
