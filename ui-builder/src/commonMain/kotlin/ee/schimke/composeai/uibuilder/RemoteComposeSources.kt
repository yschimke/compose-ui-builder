package ee.schimke.composeai.uibuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one component every [RemoteComposeSource] is inserted as; the palette, reducer and renderer
 * must agree on it.
 */
const val REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID: String = "remote-compose/document"

/**
 * The Lottie element: `remote-m3` synthesizes it, this editor resolves its `url` into `json` and
 * draws it, and `RemoteContentEmitter` compiles the `json` into the exported document.
 */
const val LOTTIE_COMPONENT_ID: String = "remote-m3/lottie"

/**
 * One published Remote Compose document the pinned catalog offers as authoring content, as bytes
 * for the `remote-compose/document` component's `documentBase64`.
 *
 * Not a [ee.schimke.composeai.uibuilder.capability.ComponentCapability]: `remote-m3` publishes
 * hundreds of stickers that are all the same component with different bytes.
 *
 * @property id the preview id in the serving catalog — what `render/<id>.rc` addresses.
 * @property label the catalog's own display name for it.
 * @property group the component family, for the palette's headings.
 */
data class RemoteComposeSource(val id: String, val label: String, val group: String)

/**
 * The Remote Compose documents in a `GET /{system}/api/previews` response, from the server's
 * `remoteCompose` flag (no per-preview probing). Tolerant of unknown keys.
 */
fun parseRemoteComposeSources(previewsJson: String): List<RemoteComposeSource> =
  PREVIEWS_JSON.decodeFromString(PreviewsPayload.serializer(), previewsJson)
    .previews
    .filter { it.remoteCompose && it.id.isNotBlank() }
    .map {
      val family =
        it.id.substringBefore(SOURCE_GROUP_SEPARATOR, missingDelimiterValue = UNGROUPED_SOURCES)
      RemoteComposeSource(
        id = it.id,
        // When the label just repeats the technical sticker id, derive a readable name from the
        // state words after the family separator.
        label = sourceLabel(it.id, it.label),
        // Keep the stable family key for ordering and search. The panel humanises it at the final
        // presentation edge, rather than changing the identity every consumer already groups by.
        group = family.ifBlank { UNGROUPED_SOURCES },
      )
    }
    .distinctBy(RemoteComposeSource::id)
    // One choice per semantic sticker: the catalog publishes one per capture frame, but the
    // design's environment supplies the frame.
    .groupBy(::sourceWithoutCaptureFrame)
    .values
    .map { captures ->
      val source = captures.minWith(compareBy(::captureFrameRank, RemoteComposeSource::id))
      if (captures.size == 1) source else source.copy(label = source.label.withoutCaptureFrame())
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
 * `appcard__ideal__default__compact`: the first segment is the component family, used for headings;
 * ids without one go under [UNGROUPED_SOURCES].
 */
private const val SOURCE_GROUP_SEPARATOR = "__"

private const val UNGROUPED_SOURCES = "documents"

/** A catalog label when it has one; otherwise the useful, non-plumbing part of its sticker id. */
internal fun sourceLabel(id: String, catalogLabel: String): String {
  val label = catalogLabel.trim()
  val technical = label.isEmpty() || label == id || SOURCE_GROUP_SEPARATOR in label
  if (!technical) return label
  val states =
    id
      .split(SOURCE_GROUP_SEPARATOR)
      .drop(1)
      // `ideal` is a capture lane, not a property somebody choosing a component understands.
      .filterNot { it.equals("ideal", ignoreCase = true) }
      .map(::humanizeSourceSlug)
      .filter(String::isNotBlank)
  return states.joinToString(" · ").ifBlank {
    humanizeSourceSlug(id.substringBefore(SOURCE_GROUP_SEPARATOR))
  }
}

/** Turns the catalog's stable kebab-case machine word into a sentence-case palette label. */
internal fun humanizeSourceSlug(value: String): String =
  value
    .trim()
    .replace('-', ' ')
    .replace('_', ' ')
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .joinToString(" ")
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Identity of the authored state, excluding the preview frame it happened to be captured in. */
private fun sourceWithoutCaptureFrame(source: RemoteComposeSource): String {
  val parts = source.id.split(SOURCE_GROUP_SEPARATOR)
  val last = parts.lastOrNull()?.lowercase()
  return if (parts.size > 1 && last in CAPTURE_FRAME_NAMES)
    parts.dropLast(1).joinToString(SOURCE_GROUP_SEPARATOR)
  else source.id
}

/** Prefer the compact capture when several equivalent transport documents are available. */
private fun captureFrameRank(source: RemoteComposeSource): Int =
  when (source.id.substringAfterLast(SOURCE_GROUP_SEPARATOR).lowercase()) {
    "compact",
    "small" -> 0
    "medium",
    "standard" -> 1
    "expanded",
    "large" -> 2
    else -> 3
  }

private fun String.withoutCaptureFrame(): String {
  val separator = " · "
  val parts = split(separator)
  return if (parts.lastOrNull()?.lowercase() in CAPTURE_FRAME_NAMES)
    parts.dropLast(1).joinToString(separator)
  else this
}

private val CAPTURE_FRAME_NAMES =
  setOf("compact", "small", "medium", "standard", "expanded", "large")

private val PREVIEWS_JSON = Json { ignoreUnknownKeys = true }

@Serializable private data class PreviewsPayload(val previews: List<PreviewEntry> = emptyList())

@Serializable
private data class PreviewEntry(
  val id: String = "",
  val label: String = "",
  val remoteCompose: Boolean = false,
)
