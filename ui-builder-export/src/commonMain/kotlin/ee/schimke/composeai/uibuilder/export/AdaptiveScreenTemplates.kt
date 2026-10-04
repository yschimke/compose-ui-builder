package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Material adaptive starting points, available to both server seeds and the New design chooser. */
object AdaptiveScreenTemplates {
  val ids: Set<String> =
    setOf("list-detail", "supporting-pane", "adaptive-feed", "adaptive-navigation")

  fun document(
    templateId: String,
    designId: String,
    catalogPin: JsonObject,
    environment: JsonObject,
  ): UiBuilderDocument {
    fun properties(json: String) = Json.parseToJsonElement(json) as JsonObject
    fun modifiers(json: String) = Json.parseToJsonElement(json) as JsonArray
    val nodes =
      when (templateId) {
        "list-detail" ->
          mapOf(
            "panes" to
              UiBuilderNode(
                id = "panes",
                componentId = "layout/list-detail-pane-scaffold",
                properties =
                  properties(
                    """{"layoutMode":{"type":"enum","value":"adaptive"},"listPaneVisible":{"type":"bool","value":true},"detailPaneVisible":{"type":"bool","value":true},"paneSizing":{"type":"enum","value":"fixedStart"},"fixedPaneWidthDp":{"type":"float","value":360},"activePaneIndex":{"type":"state","variable":"pane"}}"""
                  ),
                modifiers = modifiers("""[{"type":"fillMaxSize"}]"""),
                slots =
                  mapOf(
                    "listPane" to listOf("main"),
                    "detailPane" to listOf("support"),
                    "extraPane" to listOf(),
                  ),
              ),
            "main" to
              UiBuilderNode(
                id = "main",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("main-title", "main-copy", "open-item")),
              ),
            "main-title" to
              UiBuilderNode(
                id = "main-title",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"List"},"style":{"type":"enum","value":"headlineMedium"}}"""
                  ),
                slots = mapOf(),
              ),
            "main-copy" to
              UiBuilderNode(
                id = "main-copy",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Select an item to open its details."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "support" to
              UiBuilderNode(
                id = "support",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots =
                  mapOf("children" to listOf("support-title", "support-copy", "back-to-list")),
              ),
            "support-title" to
              UiBuilderNode(
                id = "support-title",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Detail"},"style":{"type":"enum","value":"titleLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "support-copy" to
              UiBuilderNode(
                id = "support-copy",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"The selected item appears here. Return to the list to choose another."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "screen" to
              UiBuilderNode(
                id = "screen",
                componentId = "layout/scaffold",
                slots = mapOf("content" to listOf("panes")),
              ),
            "open-item" to
              UiBuilderNode(
                id = "open-item",
                componentId = "m3/button",
                properties = properties("""{"style":{"type":"enum","value":"filled"}}"""),
                eventBindings =
                  properties("""{"click":[{"type":"select","variable":"pane","value":1}]}"""),
                slots = mapOf("content" to listOf("open-item-label")),
              ),
            "open-item-label" to
              UiBuilderNode(
                id = "open-item-label",
                componentId = "m3/text",
                properties = properties("""{"text":{"type":"string","value":"Open item"}}"""),
              ),
            "back-to-list" to
              UiBuilderNode(
                id = "back-to-list",
                componentId = "m3/button",
                properties = properties("""{"style":{"type":"enum","value":"filled"}}"""),
                eventBindings =
                  properties("""{"click":[{"type":"select","variable":"pane","value":0}]}"""),
                slots = mapOf("content" to listOf("back-to-list-label")),
              ),
            "back-to-list-label" to
              UiBuilderNode(
                id = "back-to-list-label",
                componentId = "m3/text",
                properties = properties("""{"text":{"type":"string","value":"Back to list"}}"""),
              ),
          )
        "supporting-pane" ->
          mapOf(
            "panes" to
              UiBuilderNode(
                id = "panes",
                componentId = "layout/supporting-pane-scaffold",
                properties =
                  properties(
                    """{"layoutMode":{"type":"enum","value":"adaptive"},"mainPaneVisible":{"type":"bool","value":true},"supportingPaneVisible":{"type":"bool","value":true},"paneSizing":{"type":"enum","value":"fixedEnd"},"fixedPaneWidthDp":{"type":"float","value":360},"supportingPanePreferredWidthDp":{"type":"float","value":360},"paneSpacingDp":{"type":"float","value":16}}"""
                  ),
                modifiers = modifiers("""[{"type":"fillMaxSize"}]"""),
                slots = mapOf("mainPane" to listOf("main"), "supportingPane" to listOf("support")),
              ),
            "main" to
              UiBuilderNode(
                id = "main",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("main-title", "main-copy")),
              ),
            "main-title" to
              UiBuilderNode(
                id = "main-title",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Main content"},"style":{"type":"enum","value":"headlineMedium"}}"""
                  ),
                slots = mapOf(),
              ),
            "main-copy" to
              UiBuilderNode(
                id = "main-copy",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Replace this with your primary content."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "support" to
              UiBuilderNode(
                id = "support",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("support-title", "support-copy")),
              ),
            "support-title" to
              UiBuilderNode(
                id = "support-title",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Related content"},"style":{"type":"enum","value":"titleLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "support-copy" to
              UiBuilderNode(
                id = "support-copy",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Add context or tools for the main content here."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "screen" to
              UiBuilderNode(
                id = "screen",
                componentId = "layout/scaffold",
                slots = mapOf("content" to listOf("panes")),
              ),
          )
        "adaptive-feed" ->
          mapOf(
            "screen" to
              UiBuilderNode(
                id = "screen",
                componentId = "layout/scaffold",
                slots = mapOf("content" to listOf("feed")),
              ),
            "feed" to
              UiBuilderNode(
                id = "feed",
                componentId = "layout/lazy-grid",
                properties =
                  properties(
                    """{"columns":{"type":"adaptiveGrid","minimumCellWidthDp":280},"scrollStateKey":{"type":"string","value":"feed-scroll"},"horizontalSpacingDp":{"type":"float","value":16},"verticalSpacingDp":{"type":"float","value":16}}"""
                  ),
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("items" to listOf("card-1", "card-2", "card-3")),
              ),
            "card-1" to
              UiBuilderNode(
                id = "card-1",
                componentId = "m3/card",
                slots = mapOf("content" to listOf("card-body-1")),
              ),
            "card-body-1" to
              UiBuilderNode(
                id = "card-body-1",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("card-title-1", "card-copy-1")),
              ),
            "card-title-1" to
              UiBuilderNode(
                id = "card-title-1",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Item 1"},"style":{"type":"enum","value":"titleLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "card-copy-1" to
              UiBuilderNode(
                id = "card-copy-1",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Replace this with feed content."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "card-2" to
              UiBuilderNode(
                id = "card-2",
                componentId = "m3/card",
                slots = mapOf("content" to listOf("card-body-2")),
              ),
            "card-body-2" to
              UiBuilderNode(
                id = "card-body-2",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("card-title-2", "card-copy-2")),
              ),
            "card-title-2" to
              UiBuilderNode(
                id = "card-title-2",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Item 2"},"style":{"type":"enum","value":"titleLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "card-copy-2" to
              UiBuilderNode(
                id = "card-copy-2",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Replace this with feed content."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "card-3" to
              UiBuilderNode(
                id = "card-3",
                componentId = "m3/card",
                slots = mapOf("content" to listOf("card-body-3")),
              ),
            "card-body-3" to
              UiBuilderNode(
                id = "card-body-3",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("card-title-3", "card-copy-3")),
              ),
            "card-title-3" to
              UiBuilderNode(
                id = "card-title-3",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Item 3"},"style":{"type":"enum","value":"titleLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "card-copy-3" to
              UiBuilderNode(
                id = "card-copy-3",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Replace this with feed content."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
          )
        "adaptive-navigation" ->
          mapOf(
            "navigation" to
              UiBuilderNode(
                id = "navigation",
                componentId = "m3/navigation-suite-scaffold",
                modifiers = modifiers("""[{"type":"fillMaxSize"}]"""),
                slots =
                  mapOf("navigationItems" to listOf("home", "saved"), "content" to listOf("body")),
              ),
            "body" to
              UiBuilderNode(
                id = "body",
                componentId = "layout/column",
                modifiers =
                  modifiers(
                    """[{"type":"fillMaxSize"},{"type":"padding","startDp":24,"topDp":24,"endDp":24,"bottomDp":24}]"""
                  ),
                slots = mapOf("children" to listOf("title", "copy")),
              ),
            "title" to
              UiBuilderNode(
                id = "title",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Your app"},"style":{"type":"enum","value":"headlineMedium"}}"""
                  ),
                slots = mapOf(),
              ),
            "copy" to
              UiBuilderNode(
                id = "copy",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Add destination content here."},"style":{"type":"enum","value":"bodyLarge"}}"""
                  ),
                slots = mapOf(),
              ),
            "home" to
              UiBuilderNode(
                id = "home",
                componentId = "m3/navigation-suite-item",
                properties = properties("""{"selected":{"type":"bool","value":true}}"""),
                slots = mapOf("icon" to listOf("home-icon"), "label" to listOf("home-label")),
              ),
            "home-icon" to
              UiBuilderNode(
                id = "home-icon",
                componentId = "m3/icon",
                properties =
                  properties(
                    """{"iconKey":{"type":"string","value":"home"},"contentDescription":{"type":"string","value":"Home"}}"""
                  ),
                slots = mapOf(),
              ),
            "home-label" to
              UiBuilderNode(
                id = "home-label",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Home"},"style":{"type":"enum","value":"labelMedium"}}"""
                  ),
                slots = mapOf(),
              ),
            "saved" to
              UiBuilderNode(
                id = "saved",
                componentId = "m3/navigation-suite-item",
                properties = properties("""{"selected":{"type":"bool","value":false}}"""),
                slots = mapOf("icon" to listOf("saved-icon"), "label" to listOf("saved-label")),
              ),
            "saved-icon" to
              UiBuilderNode(
                id = "saved-icon",
                componentId = "m3/icon",
                properties =
                  properties(
                    """{"iconKey":{"type":"string","value":"star"},"contentDescription":{"type":"string","value":"Saved"}}"""
                  ),
                slots = mapOf(),
              ),
            "saved-label" to
              UiBuilderNode(
                id = "saved-label",
                componentId = "m3/text",
                properties =
                  properties(
                    """{"text":{"type":"string","value":"Saved"},"style":{"type":"enum","value":"labelMedium"}}"""
                  ),
                slots = mapOf(),
              ),
          )
        else -> error("Unknown adaptive template: $templateId")
      }
    val root =
      when (templateId) {
        "list-detail" -> "screen"
        "supporting-pane" -> "screen"
        "adaptive-feed" -> "screen"
        "adaptive-navigation" -> "navigation"
        else -> error("Unknown adaptive template: $templateId")
      }
    return blankUiBuilderDocument(designId, catalogPin, mobileScreenEnvironment(environment))
      .copy(
        title =
          templateId.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) },
        stateVariables =
          if (templateId == "list-detail" && UiBuilderBuildFeatures.remoteCompose)
            properties(
              """{"pane":{"type":"selection","valueType":"int","nullable":false,"initialValue":0,"persistence":"preview"}}"""
            )
          else JsonObject(emptyMap()),
        roots = listOf(root),
        nodes =
          if (UiBuilderBuildFeatures.remoteCompose) nodes
          else
            nodes
              .filterKeys {
                templateId != "list-detail" ||
                  it !in setOf("open-item", "open-item-label", "back-to-list", "back-to-list-label")
              }
              .mapValues { (_, node) ->
                node.copy(
                  slots =
                    node.slots.mapValues { (_, ids) ->
                      ids.filter { it != "open-item" && it != "back-to-list" }
                    },
                  eventBindings = JsonObject(emptyMap()),
                  properties =
                    if ("activePaneIndex" in node.properties)
                      JsonObject(
                        node.properties +
                          ("activePaneIndex" to properties("""{"type":"int","value":0}"""))
                      )
                    else node.properties,
                )
              },
      )
  }
}
