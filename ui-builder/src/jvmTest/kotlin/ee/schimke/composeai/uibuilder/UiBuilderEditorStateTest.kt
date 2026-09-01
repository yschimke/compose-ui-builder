package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderEditorStateTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `catalog search preserves scaffold container and composable kinds`() {
    val items = reducer.catalogItems("")

    assertTrue(items.any { it.kind == EditorComponentKind.Scaffold })
    assertTrue(items.any { it.kind == EditorComponentKind.Container })
    assertTrue(items.any { it.kind == EditorComponentKind.Composable })
    assertEquals(listOf("m3/text"), reducer.catalogItems("text").map { it.componentId })
    assertEquals(
      "discover-grid.items",
      reducer.dropTargetLabel(reducer.initial(document, "discover-grid")),
    )
  }

  @Test
  fun `insert edit and reorder are collaboration reducer operations`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/text"))
    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponent(componentId = "m3/text", target = target),
      )
    val insertedId = "editor-m3-text-001"

    assertTrue(inserted.lastOutcome is CommandOutcome.Accepted, inserted.lastOutcome.toString())
    assertEquals(109, inserted.document.revision)
    assertEquals(insertedId, inserted.selectedNodeId)
    assertEquals(
      insertedId,
      inserted.document.nodes.getValue("discover-grid").slots.getValue("items").last(),
    )

    val edited =
      reducer.reduce(
        inserted,
        UiBuilderEditorEvent.CommitProperty(insertedId, "text", "From canvas"),
      )
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    assertEquals(110, edited.document.revision)
    assertEquals(
      "From canvas",
      edited.document.nodes
        .getValue(insertedId)
        .properties
        .getValue("text")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )

    val move = requireNotNull(reducer.moveTarget(edited, insertedId, EditorMoveDirection.Before))
    val moved = reducer.reduce(edited, move)
    val children = moved.document.nodes.getValue("discover-grid").slots.getValue("items")
    assertIs<CommandOutcome.Accepted>(moved.lastOutcome)
    assertEquals(111, moved.document.revision)
    assertEquals(insertedId, children[children.lastIndex - 1])
  }

  @Test
  fun `configured live actor emits one uniquely scoped accepted submission`() {
    val liveReducer = UiBuilderEditorReducer(catalog, actorId = "github:alice", clientId = "tab-a")
    val initial = liveReducer.initial(document, selectedNodeId = "main-episode-title")
    val edited =
      liveReducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("main-episode-title", "text", "Shared title"),
      )

    val submission =
      assertIs<EditorSubmission.Batch>(liveReducer.acceptedSubmission(initial, edited))
    assertEquals("github:alice", submission.command.actorId)
    assertEquals("tab-a", submission.command.clientId)
    assertEquals("tab-a-editor-operation-0001", submission.command.operationId)
    assertNull(liveReducer.acceptedSubmission(edited, edited))
  }

  @Test
  fun `screen environment is document level validated and undoable without changing nodes`() {
    val initial = reducer.initial(document, selectedNodeId = "main-episode-title")
    val originalNodes = initial.document.nodes
    val updated =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          ScreenEnvironmentSettings(
            widthDp = 412,
            heightDp = 915,
            density = 3.0,
            fontScale = 1.4,
            locale = "ar-EG",
            theme = EditorScreenTheme.Dark,
            layoutDirection = EditorLayoutDirection.Rtl,
          )
        ),
      )

    assertIs<CommandOutcome.Accepted>(updated.lastOutcome)
    assertEquals(originalNodes, updated.document.nodes)
    assertEquals(
      412,
      updated.document.environment.getValue("widthDp").jsonPrimitive.content.toInt(),
    )
    assertEquals(
      1.4,
      updated.document.environment.getValue("fontScale").jsonPrimitive.content.toDouble(),
    )
    assertEquals(
      "rtl",
      updated.document.environment.getValue("layoutDirection").jsonPrimitive.content,
    )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, updated))
    assertTrue(submission.command.operations.all { it is DesignOperation.SetEnvironment })

    val undone = reducer.reduce(updated, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome)
    assertEquals(document.environment, undone.document.environment)
    assertEquals(originalNodes, undone.document.nodes)
    val redone = reducer.reduce(undone, UiBuilderEditorEvent.Redo)
    assertEquals(412, redone.document.environment.getValue("widthDp").jsonPrimitive.content.toInt())
    assertEquals(originalNodes, redone.document.nodes)

    val rejected =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(fontScale = 0.1)
        ),
      )
    assertIs<CommandOutcome.Rejected>(rejected.lastOutcome)
    assertEquals(document, rejected.document)
  }

  @Test
  fun `Google icon edit uses the typed inspector collaboration command`() {
    val initial = reducer.initial(document, selectedNodeId = "search-leading-icon")
    val edited =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("search-leading-icon", "iconKey", "home"),
      )

    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    assertEquals(
      "home",
      edited.document.nodes
        .getValue("search-leading-icon")
        .properties
        .getValue("iconKey")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, edited))
    assertIs<DesignOperation.SetProperty>(submission.command.operations.single())
  }

  @Test
  fun `Google icon picker and capability allowlist stay in sync`() {
    val allowed =
      catalog.componentsById
        .getValue("m3/icon")
        .propertiesByName
        .getValue("iconKey")
        .allowedValues
        .map { it.jsonPrimitive.content }
        .toSet()

    assertEquals(GoogleMaterialIcons.map { it.key }.toSet(), allowed)
    assertTrue(GoogleMaterialIcons.size >= 40)
    val field =
      reducer
        .propertyFields(reducer.initial(document, selectedNodeId = "search-leading-icon"))
        .single { it.name == "iconKey" }
    assertEquals(EditorPropertyControl.Enum, field.control)
    assertEquals(allowed, field.choices.toSet())
  }

  @Test
  fun `typed property edit rejects an icon outside the Google catalog`() {
    val initial = reducer.initial(document, selectedNodeId = "search-leading-icon")
    val edited =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("search-leading-icon", "iconKey", "notInCatalog"),
      )

    assertIs<CommandOutcome.Rejected>(edited.lastOutcome)
    assertEquals(document, edited.document)
  }

  @Test
  fun `top level theme is one validated collaborative batch`() {
    val initial = reducer.initial(document)
    val themed =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.ApplyTheme(
          EditorThemeSettings(
            primaryColor = "#FFFF6B8A",
            backgroundColor = "#FF101525",
            surfaceColor = "#FF202A44",
            contentColor = "#FFF4F6FF",
            typeScale = 1.15f,
            cornerRadiusDp = 24f,
          )
        ),
      )

    assertIs<CommandOutcome.Accepted>(themed.lastOutcome)
    assertEquals(document.revision + 1, themed.document.revision)
    val root = themed.document.nodes.getValue("root-surface")
    assertEquals(
      "#FFFF6B8A",
      root.properties.getValue(THEME_PRIMARY).jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertEquals(
      "1.15",
      root.properties.getValue(THEME_TYPE_SCALE).jsonObject.getValue("value").jsonPrimitive.content,
    )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, themed))
    assertEquals(6, submission.command.operations.size)
    assertTrue(
      reducer.propertyFields(themed.copy(selectedNodeId = "root-surface")).none {
        it.name.startsWith("theme")
      }
    )

    val undone = reducer.reduce(themed, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome)
    assertFalse(THEME_PRIMARY in undone.document.nodes.getValue("root-surface").properties)
  }

  @Test
  fun `invalid theme input is rejected without a partial document change`() {
    val initial = reducer.initial(document)
    val attempted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.ApplyTheme(
          EditorThemeSettings(primaryColor = "purple", typeScale = 2f)
        ),
      )

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(document, attempted.document)
  }

  @Test
  fun `insert rejects a stale or incompatible destination without changing the document`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val attempted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponent(
          componentId = "m3/text",
          target = ParentSlot("root-surface", "content"),
        ),
      )

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(document.revision, attempted.document.revision)
    assertEquals(document.nodes, attempted.document.nodes)
  }

  @Test
  fun `inspector derives typed fields for scaffold container and Jetcaster text`() {
    val scaffold = reducer.propertyFields(reducer.initial(document, "pane-scaffold"))
    assertEquals(EditorPropertyControl.Enum, scaffold.single { it.name == "layoutMode" }.control)
    assertEquals(
      EditorPropertyControl.Number,
      scaffold.single { it.name == "mainPanePreferredWidthDp" }.control,
    )
    assertEquals(
      EditorPropertyControl.Boolean,
      scaffold.single { it.name == "supportingPaneVisible" }.control,
    )

    val container = reducer.propertyFields(reducer.initial(document, "main-scaffold"))
    assertTrue(
      container.any { it.name == "loading" && it.control == EditorPropertyControl.Boolean }
    )

    val text = reducer.propertyFields(reducer.initial(document, "detail-podcast-title"))
    assertEquals(EditorPropertyControl.Text, text.single { it.name == "text" }.control)
    assertEquals(EditorPropertyControl.Color, text.single { it.name == "color" }.control)
    assertEquals(EditorPropertyControl.Enum, text.single { it.name == "style" }.control)
    assertEquals(EditorPropertyControl.Number, text.single { it.name == "maxLines" }.control)
    assertEquals(EditorPropertyControl.Boolean, text.single { it.name == "softWrap" }.control)
    assertTrue(
      text.none {
        it.name in setOf("fontScale", "density", "locale", "theme", "viewport", "layoutDirection")
      }
    )
  }

  @Test
  fun `typed commits validate before one authoritative property operation`() {
    val initial = reducer.initial(document, "pane-scaffold")
    val invalid =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty(
          "pane-scaffold",
          "mainPanePreferredWidthDp",
          "5000",
        ),
      )
    val rejection = assertIs<CommandOutcome.Rejected>(invalid.lastOutcome)
    assertEquals("pane-scaffold", rejection.nodeId)
    assertEquals("mainPanePreferredWidthDp", rejection.field)
    assertEquals(document.revision, invalid.document.revision)
    assertNull(reducer.acceptedSubmission(initial, invalid))
    assertTrue(
      reducer
        .propertyFields(invalid)
        .single { it.name == "mainPanePreferredWidthDp" }
        .error
        ?.contains("0..4096") == true
    )

    val accepted =
      reducer.reduce(
        invalid,
        UiBuilderEditorEvent.CommitProperty(
          "pane-scaffold",
          "mainPanePreferredWidthDp",
          "800",
        ),
      )
    assertIs<CommandOutcome.Accepted>(accepted.lastOutcome)
    assertEquals(document.revision + 1, accepted.document.revision)
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(invalid, accepted))
    val operation = assertIs<DesignOperation.SetProperty>(submission.command.operations.single())
    assertEquals("pane-scaffold", operation.nodeId)
    assertEquals("mainPanePreferredWidthDp", operation.property)
    assertEquals(
      "800.0",
      operation.value.jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertNull(
      reducer.propertyFields(accepted).single { it.name == "mainPanePreferredWidthDp" }.error
    )
  }

  @Test
  fun `enum boolean and color edits preserve catalog value shapes`() {
    val initial = reducer.initial(document, "pane-scaffold")
    val enumEdited =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("pane-scaffold", "layoutMode", "twoPane"),
      )
    val booleanEdited =
      reducer.reduce(
        enumEdited,
        UiBuilderEditorEvent.CommitProperty(
          "pane-scaffold",
          "supportingPaneVisible",
          "false",
        ),
      )
    val selectedText = booleanEdited.copy(selectedNodeId = "detail-podcast-title")
    val colorEdited =
      reducer.reduce(
        selectedText,
        UiBuilderEditorEvent.CommitProperty(
          "detail-podcast-title",
          "color",
          "onSurfaceVariant",
        ),
      )

    assertIs<CommandOutcome.Accepted>(enumEdited.lastOutcome)
    assertIs<CommandOutcome.Accepted>(booleanEdited.lastOutcome)
    assertIs<CommandOutcome.Accepted>(colorEdited.lastOutcome)
    assertEquals(
      "twoPane",
      enumEdited.document.nodes
        .getValue("pane-scaffold")
        .properties
        .getValue("layoutMode")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "false",
      booleanEdited.document.nodes
        .getValue("pane-scaffold")
        .properties
        .getValue("supportingPaneVisible")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "onSurfaceVariant",
      colorEdited.document.nodes
        .getValue("detail-podcast-title")
        .properties
        .getValue("color")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `Text line ranges and positive weight reject with a located error`() {
    val initial = reducer.initial(document, "detail-podcast-title")
    val maxExpanded =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("detail-podcast-title", "maxLines", "5"),
      )
    val minEdited =
      reducer.reduce(
        maxExpanded,
        UiBuilderEditorEvent.CommitProperty("detail-podcast-title", "minLines", "4"),
      )
    val invalidMax =
      reducer.reduce(
        minEdited,
        UiBuilderEditorEvent.CommitProperty("detail-podcast-title", "maxLines", "3"),
      )
    val maxRejection = assertIs<CommandOutcome.Rejected>(invalidMax.lastOutcome)
    assertEquals("detail-podcast-title", maxRejection.nodeId)
    assertEquals("maxLines", maxRejection.field)
    assertEquals(minEdited.document.revision, invalidMax.document.revision)
    assertTrue(maxRejection.message.contains("minLines"))

    val invalidWeight =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitProperty("detail-podcast-title", "weight", "0"),
      )
    val weightRejection = assertIs<CommandOutcome.Rejected>(invalidWeight.lastOutcome)
    assertEquals("weight", weightRejection.field)
    assertTrue(weightRejection.message.contains("0.1..100"))
  }

  @Test
  fun `required search input subtree is capability valid and committed atomically`() {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/search-bar"))
    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponent("m3/search-bar", target),
      )

    assertTrue(inserted.lastOutcome is CommandOutcome.Accepted, inserted.lastOutcome.toString())
    assertEquals(110, inserted.document.nodes.size)
    val searchBar = inserted.document.nodes.getValue("editor-m3-search-bar-001")
    val inputId = searchBar.slots.getValue("inputField").single()
    assertEquals("m3/search-input-field", inserted.document.nodes.getValue(inputId).componentId)
  }

  @Test
  fun `cyclic required slot defaults reject without applying partial nodes`() {
    val box = catalog.componentsById.getValue("layout/box")
    val cyclicCatalog =
      catalog.copy(
        components =
          catalog.components.map { component ->
            if (component.componentId == box.componentId) {
              component.copy(
                slots =
                  component.slots.map { slot ->
                    if (slot.name == "children") {
                      slot.copy(
                        cardinality = slot.cardinality.copy(min = 1),
                        acceptedRoles = listOf("Container"),
                      )
                    } else slot
                  }
              )
            } else component
          }
      )
    val cyclicReducer = UiBuilderEditorReducer(cyclicCatalog)
    val initial = cyclicReducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(cyclicReducer.dropTarget(initial, "layout/box"))
    val attempted =
      cyclicReducer.reduce(initial, UiBuilderEditorEvent.InsertComponent("layout/box", target))

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(document.revision, attempted.document.revision)
    assertEquals(document.nodes, attempted.document.nodes)
  }

  @Test
  fun `layer tree follows authored slot order and records parents`() {
    val rows = reducer.treeRows(document)

    assertEquals(108, rows.size)
    assertEquals("root-surface", rows.first().nodeId)
    val discover = rows.first { it.nodeId == "discover-grid" }
    assertEquals(5, discover.depth)
    assertEquals(ParentSlot("main-content", "children"), discover.parent)
  }

  @Test
  fun `duplicate copies a complete subtree in one validated batch`() {
    val initial = reducer.initial(document, selectedNodeId = "main-episode-card")
    val sourceIds = subtreeIds(document, "main-episode-card")
    val duplicated = reducer.reduce(initial, UiBuilderEditorEvent.DuplicateSelected)
    val copyId = "main-episode-card-copy-001"

    assertIs<CommandOutcome.Accepted>(duplicated.lastOutcome)
    assertEquals(document.revision + 1, duplicated.document.revision)
    assertEquals(document.nodes.size + sourceIds.size, duplicated.document.nodes.size)
    assertEquals(copyId, duplicated.selectedNodeId)
    assertEquals(
      listOf("main-episode-card", copyId),
      duplicated.document.nodes.getValue("discover-grid").slots.getValue("items").takeLast(2),
    )
    assertEquals(
      document.nodes.getValue("main-episode-card").properties,
      duplicated.document.nodes.getValue(copyId).properties,
    )
    assertTrue(
      duplicated.document.nodes
        .getValue(copyId)
        .slots
        .getValue("content")
        .single()
        .startsWith("$copyId-")
    )
  }

  @Test
  fun `delete and duplicate reject before violating roots or slot cardinality`() {
    val requiredChild = reducer.initial(document, selectedNodeId = "main-content")

    assertFalse(reducer.canDeleteSelected(requiredChild))
    assertFalse(reducer.canDuplicateSelected(requiredChild))
    val deleted = reducer.reduce(requiredChild, UiBuilderEditorEvent.DeleteSelected)
    val duplicated = reducer.reduce(requiredChild, UiBuilderEditorEvent.DuplicateSelected)

    assertIs<CommandOutcome.Rejected>(deleted.lastOutcome)
    assertIs<CommandOutcome.Rejected>(duplicated.lastOutcome)
    assertEquals(document, deleted.document)
    assertEquals(document, duplicated.document)

    val soleRoot = reducer.initial(document, selectedNodeId = "root-surface")
    assertFalse(reducer.canDeleteSelected(soleRoot))
    assertEquals(document, reducer.reduce(soleRoot, UiBuilderEditorEvent.DeleteSelected).document)
  }

  @Test
  fun `delete removes the selected subtree atomically and undo redo restore actor history`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/text"))
    val inserted = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent("m3/text", target))
    val insertedId = requireNotNull(inserted.selectedNodeId)
    val deleted = reducer.reduce(inserted, UiBuilderEditorEvent.DeleteSelected)

    assertIs<CommandOutcome.Accepted>(deleted.lastOutcome)
    assertEquals(document.revision + 2, deleted.document.revision)
    assertNull(deleted.document.nodes[insertedId])
    assertEquals("discover-grid", deleted.selectedNodeId)
    assertTrue(deleted.canUndo)

    val undone = reducer.reduce(deleted, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome)
    assertTrue(insertedId in undone.document.nodes)
    assertEquals(insertedId, undone.selectedNodeId)
    assertTrue(undone.canRedo)

    val redone = reducer.reduce(undone, UiBuilderEditorEvent.Redo)
    assertIs<CommandOutcome.Accepted>(redone.lastOutcome)
    assertNull(redone.document.nodes[insertedId])
    assertEquals("discover-grid", redone.selectedNodeId)
    assertFalse(redone.canRedo)
  }

  @Test
  fun `undo targets the latest active editor operation not another actor command`() {
    val initial = reducer.initial(document, selectedNodeId = "discover-grid")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/text"))
    val inserted = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent("m3/text", target))
    val otherActor =
      CollaborationReducer.apply(
        inserted.collaboration,
        DesignCommand(
          designId = document.id,
          operationId = "other-actor-move",
          actorId = "other-actor",
          clientId = "other-client",
          baseRevision = inserted.document.revision,
          operations =
            listOf(
              DesignOperation.MoveNode(
                nodeId = "main-scrim",
                parent = ParentSlot("main-background", "children"),
                afterNodeId = "main-scaffold",
              )
            ),
        ),
      )
    assertIs<CommandOutcome.Accepted>(otherActor.outcome)

    val concurrent = inserted.copy(collaboration = otherActor.state)
    val undone = reducer.reduce(concurrent, UiBuilderEditorEvent.Undo)

    assertIs<CommandOutcome.Accepted>(undone.lastOutcome)
    assertNull(undone.document.nodes["editor-m3-text-001"])
    assertEquals(
      listOf("main-scaffold", "main-scrim"),
      undone.document.nodes.getValue("main-background").slots.getValue("children"),
    )
  }

  private fun subtreeIds(document: UiBuilderDocument, rootId: String): Set<String> = buildSet {
    fun visit(nodeId: String) {
      add(nodeId)
      document.nodes.getValue(nodeId).slots.values.flatten().forEach(::visit)
    }
    visit(rootId)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
