package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A set of components another catalog contributes to this one, as the catalog declares it.
 *
 * A design is still pinned to one catalog; a pack (e.g. `confetti-mobile`, with ids
 * `confetti-mobile/<component>`) is merged by the runtime into every authoring catalog of the same
 * [UiBuilderCatalogPlatform]. This declaration lets the editor shelve and toggle a pack's
 * components and mark them as placeholders, and lets the server pick the bundle to compile against.
 *
 * Not a second pin (designs do not record packs) and not a Wasm adapter (the canvas draws a named
 * placeholder; the native lane renders the real thing). Stored in `statusSemantics` under [KEY], as
 * [UiBuilderPreviewSurfaces] explains; no declaration means no packs.
 */
data class UiBuilderComponentPack(
  /** The pack's id — the served catalog it came from, and the prefix of every component id. */
  val id: String,
  /** What the editor calls it: the shelf heading and the settings row. */
  val label: String,
  /** Which platform's catalogs it was merged into. */
  val platform: UiBuilderCatalogPlatform,
  /**
   * The served catalog whose bundle compiles a design holding one of these components, or null when
   * the pack declares none. A pack derived from a served catalog's record names that catalog.
   */
  val nativeCatalog: String?,
  /** Every component id the pack contributed, in the catalog's own order. */
  val componentIds: List<String>,
  /** A sentence about the pack for the settings row; empty when there is nothing to add. */
  val notes: String = "",
) {
  /** Whether [componentId] belongs to this pack. */
  fun owns(componentId: String): Boolean = componentId in componentIds

  /** This pack as the catalog writes it. */
  fun toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("label", label)
    put("platform", platform.wireValue)
    nativeCatalog?.let { put("nativeCatalog", it) }
    put("components", JsonArray(componentIds.map(::JsonPrimitive)))
    if (notes.isNotEmpty()) put("notes", notes)
  }

  companion object {
    /**
     * The component id a pack gives [componentName]: kebab-cased, as all builder ids are. Used both
     * by the runtime merge and the export's reverse mapping, so they cannot disagree.
     */
    fun componentId(packId: String, componentName: String): String =
      "$packId/${kebabCase(componentName)}"

    /** `SessionCard` → `session-card`; `HTTPClient` → `http-client`; `A11yBadge` → `a11y-badge`. */
    fun kebabCase(name: String): String {
      val out = StringBuilder()
      name.forEachIndexed { index, char ->
        val previous = name.getOrNull(index - 1)
        val next = name.getOrNull(index + 1)
        val boundary =
          char.isUpperCase() &&
            previous != null &&
            previous != '-' &&
            (previous.isLowerCase() ||
              previous.isDigit() ||
              (previous.isUpperCase() && next?.isLowerCase() == true))
        if (boundary) out.append('-')
        out.append(if (char.isLetterOrDigit()) char.lowercaseChar() else '-')
      }
      return out.toString().replace(Regex("-+"), "-").trim('-')
    }
  }
}

/** The packs a catalog declares, read from its `statusSemantics`. */
data class UiBuilderComponentPacks(val packs: List<UiBuilderComponentPack>) {
  private val byComponent: Map<String, UiBuilderComponentPack> =
    packs.flatMap { pack -> pack.componentIds.map { it to pack } }.toMap()

  /** The pack that contributed [componentId], or null for one of the catalog's own. */
  fun packOf(componentId: String): UiBuilderComponentPack? = byComponent[componentId]

  /** The pack with this [id], or null. */
  operator fun get(id: String): UiBuilderComponentPack? = packs.firstOrNull { it.id == id }

  val isEmpty: Boolean
    get() = packs.isEmpty()

  companion object {
    const val KEY: String = "componentPacks"

    val NONE: UiBuilderComponentPacks = UiBuilderComponentPacks(emptyList())

    /**
     * Read the declaration out of a catalog's `statusSemantics`, falling back to [NONE]. Malformed
     * or unknown-platform entries are dropped individually, so the editor still opens.
     */
    fun from(statusSemantics: JsonObject): UiBuilderComponentPacks {
      val declared = (statusSemantics[KEY] as? JsonArray) ?: return NONE
      val packs = declared.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val id = entry.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val platform =
          entry.string("platform")?.let(UiBuilderCatalogPlatform::fromWord)
            ?: return@mapNotNull null
        UiBuilderComponentPack(
          id = id,
          label = entry.string("label")?.takeIf(String::isNotBlank) ?: id,
          platform = platform,
          nativeCatalog = entry.string("nativeCatalog")?.takeIf(String::isNotBlank),
          componentIds =
            (entry["components"] as? JsonArray)
              ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
              .orEmpty(),
          notes = entry.string("notes").orEmpty(),
        )
      }
      return if (packs.isEmpty()) NONE else UiBuilderComponentPacks(packs)
    }

    private fun JsonObject.string(name: String): String? =
      this[name]?.jsonPrimitiveOrNull()?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
      this as? JsonPrimitive
  }
}

/** The packs of a serialized catalog, read from its `statusSemantics`. */
fun componentPacksOf(statusSemantics: JsonObject): UiBuilderComponentPacks =
  UiBuilderComponentPacks.from(statusSemantics)
