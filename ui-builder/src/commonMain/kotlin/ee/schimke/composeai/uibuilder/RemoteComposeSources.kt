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
 * The Lottie element, named for the same reason the one above is: three places agree on it.
 *
 * `remote-m3` synthesizes the component, this editor resolves its `url` into its `json` and draws
 * it, and `RemoteContentEmitter` compiles the `json` into the exported document.
 */
const val LOTTIE_COMPONENT_ID: String = "remote-m3/lottie"

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
      val family =
        it.id.substringBefore(SOURCE_GROUP_SEPARATOR, missingDelimiterValue = UNGROUPED_SOURCES)
      RemoteComposeSource(
        id = it.id,
        // Some Remote Compose catalogs have no authored preview label and repeat the full sticker
        // id here. That id is useful for search and diagnostics, but it is not a name: in a narrow
        // palette every row then reads `button-child__ideal__…`. Keep a real catalog label, and
        // turn only that technical fallback into the state words after the family separator.
        label = sourceLabel(it.id, it.label),
        // Keep the stable family key for ordering and search. The panel humanises it at the final
        // presentation edge, rather than changing the identity every consumer already groups by.
        group = family.ifBlank { UNGROUPED_SOURCES },
      )
    }
    .distinctBy(RemoteComposeSource::id)
    // The catalog publishes the same semantic sticker once per capture frame. A design does not:
    // the outer UI-builder environment supplies the frame the embedded document is laid out in.
    // Keep one fetchable id for transport, but present one component choice to the author.
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
 * `appcard__ideal__default__compact` — the catalog's own sticker-id convention, whose first segment
 * is the component family. Used for headings only; an id without one lands under
 * [UNGROUPED_SOURCES] rather than being dropped.
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
