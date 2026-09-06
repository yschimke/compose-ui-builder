package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A set of components another catalog contributes to this one, as the catalog declares it.
 *
 * ## What a pack is
 *
 * A design is pinned to exactly one catalog, and that is not changing: the pin is what makes a
 * reopened design render the same components it was saved with. What a pinned catalog may now carry
 * is components that are not its own. A host serving `confetti-mobile` can admit it as a **pack** —
 * every composable its published component record proves a call site for, under ids of the form
 * `confetti-mobile/<component>` — and the builder's runtime merges that pack into every authoring
 * catalog of the same [UiBuilderCatalogPlatform]. A Material 3 phone screen can then hold a
 * `SessionCard` from Confetti beside its `m3/card`, validate it, export it and render it natively,
 * while a Wear widget is offered nothing from a mobile app, because it could not call it.
 *
 * The merge is the runtime's. What the catalog carries afterwards is this declaration, so that the
 * editor can tell a pack's components apart from the catalog's own — shelve them under the pack's
 * name, let a person switch the shelf on and off from settings, and say on the canvas that a pack
 * component is drawn as a placeholder rather than as itself — and so that the server can tell which
 * served bundle a design that uses one has to be compiled against.
 *
 * ## What a pack is not
 *
 * Not a second pin. A pack's components are part of the catalog the design is pinned to, exactly as
 * `wear-m3`'s borrowed foundation components are; a design does not record which packs it drew
 * from, and one that uses none is unaffected by a host that admits some. Not a Wasm adapter either:
 * the canvas cannot link an arbitrary application's classes, so a pack component is drawn as a
 * named placeholder in the same honest shape `wear-m3`'s native-only components are, and the
 * picture comes from the native lane, compiled against the pack's own served bundle.
 *
 * ## Where it lives
 *
 * `CatalogCapabilityV1.statusSemantics`, under [KEY], beside `previewSurfaces` and `componentMenu`,
 * for the reason [UiBuilderPreviewSurfaces] gives. A catalog that says nothing has no packs, which
 * is what every catalog had before this existed.
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
     * The component id a pack gives the [componentName] it contributes.
     *
     * One rule for both sides. The runtime writes ids with it when it merges a pack, and the export
     * path rewrites them back to the record with the same function — so the two cannot disagree
     * about what `confetti-mobile/session-card` refers to. The name is kebab-cased from the
     * composable's own, because that is the spelling every other id in this builder uses
     * (`m3/icon-button`, `wear-m3/list-header`).
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
     * Read the declaration out of a catalog's `statusSemantics`, falling back to [NONE].
     *
     * Per entry rather than all-or-nothing: an entry missing its id or naming a platform this build
     * does not know is dropped, and the rest are kept. A malformed declaration must not take a
     * catalog's editor down, and the components it named are still in the catalog — they are simply
     * not shelved as a pack.
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
