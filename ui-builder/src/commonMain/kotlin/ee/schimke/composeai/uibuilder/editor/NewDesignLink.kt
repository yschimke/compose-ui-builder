package ee.schimke.composeai.uibuilder.editor

/**
 * The New design form's choices as a link: what the home page's address bar carries while someone
 * picks, and what `/new?…` creates from in one step. Every field is optional and read leniently, so
 * a link that names a catalog this host does not offer still opens the form, at its defaults.
 *
 * The query words:
 * - `new`: the device (`phone`, `watch`, `widget`, …), [NewDesignDevice.id].
 * - `kind`: the catalog — its system id (`wff-watchface`), its label in any case or spacing
 *   (`watchface`, `Watch face`), or the last word of its id (`watchface`).
 * - `group`: a single catalog's template group, where the form offers those instead of catalogs.
 * - `template`: the starting point's id (`analog-watch-face`).
 * - `title`: a title the person typed; a generated one is never written.
 * - `visibility`: `public` where the person chose it; private is the default and is not written.
 * - `type`: read only, for hand-written links — a catalog when it names one, else a device.
 */
data class NewDesignLink(
  val device: String? = null,
  val kind: String? = null,
  val group: String? = null,
  val template: String? = null,
  val title: String? = null,
  val publicRead: Boolean = false,
) {
  /** Whether the link names anything at all; an empty one leaves the form at its defaults. */
  val isEmpty: Boolean
    get() = this == NewDesignLink()

  /** The link as query pairs, in a fixed order, omitting what is unset. */
  fun toQueryPairs(): List<Pair<String, String>> = buildList {
    device?.let { add("new" to it) }
    kind?.let { add("kind" to it) }
    group?.let { add("group" to it) }
    template?.let { add("template" to it) }
    title?.takeIf { it.isNotBlank() }?.let { add("title" to it) }
    if (publicRead) add("visibility" to "public")
  }

  companion object {
    /** Every query name the link writes or reads, so a rewrite can clear stale ones first. */
    val QUERY_NAMES: List<String> =
      listOf("new", "kind", "group", "template", "title", "visibility", "type")

    /**
     * The link in [query] (already split and decoded, the last value of a repeated name winning).
     * Blank values read as absent.
     */
    fun parse(query: Map<String, String>): NewDesignLink {
      fun value(name: String) = query[name]?.trim()?.takeIf(String::isNotEmpty)
      return NewDesignLink(
        device = value("new"),
        kind = value("kind") ?: value("type"),
        group = value("group"),
        template = value("template"),
        title = value("title")?.take(MAX_DESIGN_TITLE_LENGTH),
        publicRead = value("visibility").equals("public", ignoreCase = true),
      )
    }
  }
}

/** What a [NewDesignLink] picks among the catalogs on offer. */
data class NewDesignSelection(
  val catalogSystemId: String,
  val group: String?,
  val templateId: String,
)

/**
 * The selection [link] makes among [catalogs], or null when it names nothing on offer. Each part
 * falls back on its own: an unknown template keeps the catalog it was asked under (at that
 * catalog's first starting point), an unknown catalog keeps the device, and a template alone finds
 * the catalog that offers it.
 */
fun resolveNewDesignLink(
  link: NewDesignLink,
  catalogs: List<UiBuilderNewDesignCatalog>,
): NewDesignSelection? {
  if (catalogs.isEmpty() || link.isEmpty) return null
  val byKind = link.kind?.let { word -> catalogs.firstOrNull { it.answersTo(word) } }
  // `type=watch` (a device, not a catalog) is read as `new=watch`.
  val deviceWord = link.device ?: link.kind.takeIf { byKind == null }
  val byDevice = deviceWord?.let { word ->
    val device = NewDesignDevice.of(word)
    newDesignDeviceGroups(catalogs).firstOrNull { it.first == device }?.second?.first()
  }
  val byTemplate = link.template?.let { id -> catalogs.firstOrNull { it.offers(id) } }
  // A template named under the device it belongs to (or under no device) chooses its catalog.
  val catalog =
    byKind
      ?: byTemplate?.takeIf { byDevice == null || it.newDesignDevice == byDevice.newDesignDevice }
      ?: byDevice
      ?: catalogs.singleOrNull()?.takeIf { link.group != null }
      ?: return null
  val groups = if (catalogs.size == 1) newDesignTargetGroups(catalog) else emptyList()
  val template = link.template?.let { id -> catalog.templates.firstOrNull { it.id == id } }
  val group =
    if (groups.isEmpty()) null
    else
      groups.firstOrNull { normalizedWord(it) == link.group?.let(::normalizedWord) }
        ?: template?.group?.takeIf { it in groups }
        ?: groups.first()
  val offered = templatesForTarget(catalog, group).ifEmpty { catalog.templates }
  val templateId = template?.id?.takeIf { id -> offered.any { it.id == id } } ?: offered.first().id
  return NewDesignSelection(catalog.systemId, group, templateId)
}

/** The link that reopens [selection] as the form shows it, in canonical words. */
fun newDesignLinkOf(
  selection: NewDesignSelection,
  catalogs: List<UiBuilderNewDesignCatalog>,
  title: String = "",
  publicRead: Boolean = false,
): NewDesignLink {
  val catalog = catalogs.firstOrNull { it.systemId == selection.catalogSystemId }
  return NewDesignLink(
    device = catalog?.newDesignDevice?.id,
    kind = selection.catalogSystemId,
    group = selection.group,
    template = selection.templateId,
    title = title.trim().ifEmpty { null },
    publicRead = publicRead,
  )
}

/**
 * The editor's one-click "make another" action for a design of [catalog]: "New watch face" for a
 * catalog labelled in sentence case ("Watch face"), "New Wear design" for a one-word or proper-noun
 * label, so the words always read as an action.
 */
fun newDesignLikeThisLabel(catalog: UiBuilderNewDesignCatalog): String {
  val label = catalog.label.trim().ifEmpty { newDesignTitleOf(catalog.systemId) }
  val words = label.split(' ').filter(String::isNotEmpty)
  val sentenceCase =
    words.size > 1 &&
      words.first().drop(1).all { it.isLowerCase() } &&
      words.drop(1).all { word -> word.all { it.isLowerCase() } }
  return if (sentenceCase) "New ${label.replaceFirstChar { it.lowercaseChar() }}"
  else "New $label design"
}

private fun UiBuilderNewDesignCatalog.answersTo(word: String): Boolean {
  val wanted = normalizedWord(word)
  if (wanted.isEmpty()) return false
  return normalizedWord(systemId) == wanted ||
    normalizedWord(label) == wanted ||
    normalizedWord(systemId.substringAfterLast('-')) == wanted
}

private fun UiBuilderNewDesignCatalog.offers(templateId: String): Boolean = templates.any {
  it.id == templateId
}

/** Lower case with everything but letters and digits removed: `Watch face` is `watchface`. */
private fun normalizedWord(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }
