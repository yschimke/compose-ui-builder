package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * New-design documents for the add-on catalogs, written in the vocabulary those catalogs publish.
 *
 * `wear-m3` and `remote-m3` are served only from the `ui-builder.json` their own repository
 * publishes. That vocabulary is not the one the retired Kotlin catalogs described, which the
 * in-process canvas, its parity tests and [wearScreenUiBuilderDocument] still speak:
 * - `ScreenScaffold`'s clock and scroll indicator are components in its slots, not flags.
 * - A `ListHeader` holds its text as a child.
 * - Remote text is `remote-m3/remote-text`.
 *
 * A template in the old vocabulary is a document the service refuses, so creating one answered with
 * a redirect to a design that was never made. These are the documents a new design starts as;
 * `SeedTemplateCatalogReadinessTest` validates each against the catalogs as published.
 */
internal object PublishedCatalogSeeds {

  /**
   * A Wear screen: `ScreenScaffold` with its clock and scroll indicator, over a
   * `TransformingLazyColumn` headed [title]. With [rows], the list is wear-m3-catalog's own sample
   * list of title cards.
   *
   * The list is never empty: the published `TransformingLazyColumn` requires at least one item, so
   * even the blank screen opens with its header.
   */
  fun wearScreen(
    designId: String,
    catalogPin: JsonObject,
    environment: JsonObject,
    title: String,
    rows: List<Pair<String, String>>,
  ): UiBuilderDocument {
    require(designId.isNotBlank()) { "wear screen design id must not be blank" }
    // A `wear-m3/card` holding a title and a subtitle, not the published `wear-m3/title-card`: the
    // catalog declares both, and the renderer runtime it publishes draws only the first (it has no
    // `title-card` adapter, so the row would be a named placeholder). The Kotlin exporter writes
    // this
    // pair as `TitleCard(title, subtitle)` either way.
    val rowNodes = rows.flatMapIndexed { index, (rowTitle, subtitle) ->
      listOf(
        UiBuilderNode(
          id = "row-$index",
          componentId = "wear-m3/card",
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = mapOf("content" to listOf("row-$index-lines")),
        ),
        UiBuilderNode(
          id = "row-$index-lines",
          componentId = "layout/column",
          modifiers = JsonArray(listOf(modifier("fillMaxWidth"))),
          slots = mapOf("children" to listOf("row-$index-title", "row-$index-subtitle")),
        ),
        text("row-$index-title", rowTitle),
        text("row-$index-subtitle", subtitle),
      )
    }
    val nodes =
      listOf(
        UiBuilderNode(
          id = "wear-screen",
          componentId = "wear-m3/screen-scaffold",
          slots =
            mapOf(
              "timeText" to listOf("time-text"),
              "scrollIndicator" to listOf("scroll-indicator"),
              "content" to listOf("wear-list"),
            ),
        ),
        UiBuilderNode(id = "time-text", componentId = "wear-m3/time-text"),
        UiBuilderNode(id = "scroll-indicator", componentId = "wear-m3/scroll-indicator"),
        UiBuilderNode(
          id = "wear-list",
          componentId = "wear-m3/transforming-lazy-column",
          slots = mapOf("items" to listOf("list-header") + rows.indices.map { "row-$it" }),
        ),
        UiBuilderNode(
          id = "list-header",
          componentId = "wear-m3/list-header",
          slots = mapOf("content" to listOf("list-header-text")),
        ),
        text("list-header-text", title),
      ) + rowNodes
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = if (rows.isEmpty()) "Untitled Wear screen" else "$title · Wear screen",
      revision = 0,
      catalogPin = catalogPin,
      environment = environment,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("wear-screen"),
      nodes = nodes.associateBy(UiBuilderNode::id),
    )
  }

  /**
   * [document] with its text written as published `remote-m3/remote-text`.
   *
   * The widget samples are otherwise already in the published vocabulary: the containers and the
   * `layout/` nodes are the same ids. `RemoteText` spells the size `fontSize`, and has no
   * `alignment` of its own, so a text that was aligned in its box is wrapped in a `layout/column`
   * that carries the alignment instead.
   */
  fun remoteWidget(document: UiBuilderDocument): UiBuilderDocument {
    val wrappers = mutableMapOf<String, String>()
    val nodes = buildList {
      for (node in document.nodes.values) {
        if (node.componentId != "m3/text") {
          add(node)
          continue
        }
        val alignment = node.properties["alignment"]
        val properties =
          node.properties
            .filterKeys { it != "alignment" }
            .mapKeys { (key, _) -> if (key == "fontSizeSp") "fontSize" else key }
        add(node.copy(componentId = REMOTE_TEXT_COMPONENT_ID, properties = JsonObject(properties)))
        if (alignment != null) {
          val wrapperId = "${node.id}-aligned"
          wrappers[node.id] = wrapperId
          add(
            UiBuilderNode(
              id = wrapperId,
              componentId = "layout/column",
              properties = JsonObject(mapOf("alignment" to alignment)),
              slots = mapOf("children" to listOf(node.id)),
            )
          )
        }
      }
    }
    return document.copy(
      nodes =
        nodes
          .map { node ->
            node.copy(
              slots =
                node.slots.mapValues { (_, children) ->
                  children.map { child -> wrappers[child]?.takeUnless { node.id == it } ?: child }
                }
            )
          }
          .associateBy(UiBuilderNode::id)
    )
  }

  /**
   * [document] without any node whose component [available] does not name, and without the slot
   * references to it.
   *
   * For the Jetcaster template, which carries a hidden `m3/snackbar-host`: the built-in
   * `m3-catalog` declares it, and the catalog `m3-catalog` publishes does not (it has `m3/snackbar`
   * only). The host is never visible in the template, so the design opens the same either way.
   */
  fun withoutNodes(document: UiBuilderDocument, componentIds: Set<String>): UiBuilderDocument {
    val dropped = document.nodes.values.filter { it.componentId in componentIds }.map { it.id }
    if (dropped.isEmpty()) return document
    return document.copy(
      roots = document.roots - dropped.toSet(),
      nodes =
        (document.nodes - dropped.toSet()).mapValues { (_, node) ->
          node.copy(slots = node.slots.mapValues { (_, children) -> children - dropped.toSet() })
        },
    )
  }

  private fun text(id: String, text: String): UiBuilderNode =
    UiBuilderNode(
      id = id,
      componentId = "wear-m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to
              JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(text)))
          )
        ),
    )

  private fun modifier(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))
}
