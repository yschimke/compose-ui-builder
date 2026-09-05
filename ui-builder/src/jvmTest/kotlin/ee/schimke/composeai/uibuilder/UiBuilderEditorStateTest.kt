package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderEditorStateTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
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
    assertTrue(inserted.lastOutcome is CommandOutcome.Accepted, inserted.lastOutcome.toString())
    // Taken from the reducer rather than spelled out: a generated id carries the operation prefix
    // so two clients cannot propose the same one, and the exact shape is not what this asserts.
    val insertedId = assertNotNull(inserted.selectedNodeId)
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
      reducer.propertyFields(themed.copy(selection = listOf("root-surface"))).none {
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
    val selectedText = booleanEdited.copy(selection = listOf("detail-podcast-title"))
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
    // Four nodes, not two: the required `inputField` fill resolves to a search field, and that
    // field's starter content brings its placeholder and its magnifier with it.
    // `StarterContentTest`
    // owns what is seeded; this asserts only that the whole subtree lands in one accepted command.
    assertEquals(112, inserted.document.nodes.size)
    val searchBar = inserted.document.nodes.getValue(assertNotNull(inserted.selectedNodeId))
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

    assertIs<CommandOutcome.Accepted>(duplicated.lastOutcome)
    val copyId = assertNotNull(duplicated.selectedNodeId)
    assertEquals(document.revision + 1, duplicated.document.revision)
    assertEquals(document.nodes.size + sourceIds.size, duplicated.document.nodes.size)
    assertEquals(copyId, duplicated.selectedNodeId)
    assertEquals(
      listOf("main-episode-card", copyId),
      duplicated.document.nodes.getValue("discover-grid").slots.getValue("items").takeLast(2),
    )
    // Every property is carried across **except** the ones that identify the instance. The card
    // sits in a lazy grid and the exporter turns `stableKey` into that item's `key(…)`; two
    // siblings sharing one is a runtime failure in Compose, not a cosmetic clash. The copy takes
    // its own node id, which is unique by construction.
    assertEquals(
      document.nodes.getValue("main-episode-card").properties.filterKeys { it != "stableKey" },
      duplicated.document.nodes.getValue(copyId).properties.filterKeys { it != "stableKey" },
    )
    assertEquals(
      copyId,
      duplicated.document.nodes
        .getValue(copyId)
        .properties
        .getValue("stableKey")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
    )
    assertEquals(
      "episode-140",
      document.nodes
        .getValue("main-episode-card")
        .properties
        .getValue("stableKey")
        .jsonObject
        .getValue("value")
        .jsonPrimitive
        .content,
      "the original keeps its own key",
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
    assertNull(undone.document.nodes[assertNotNull(inserted.selectedNodeId)])
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

  @Test
  fun `a copied subtree pastes into a different parent, with fresh ids for every node`() {
    val copied =
      reducer.reduce(reducer.initial(document, "discover-grid"), UiBuilderEditorEvent.CopySelected)
    val clipboard = assertIs<EditorClipboard>(copied.clipboard)
    assertEquals(listOf("discover-grid"), clipboard.rootNodeIds)
    // The whole subtree, not just the root — children live in the document's flat `nodes` map and
    // copying the root alone would paste an empty container.
    assertTrue(clipboard.nodes.size > 1, clipboard.nodes.keys.toString())

    val pasted = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    val newId = assertIs<String>(pasted.selectedNodeId)
    assertTrue(newId !in document.nodes, newId)
    assertEquals(
      document.nodes.getValue("discover-grid").componentId,
      pasted.document.nodes.getValue(newId).componentId,
    )
    // Every node of the pasted subtree is new; none reuses an id already in the document.
    val added = pasted.document.nodes.keys - document.nodes.keys
    assertEquals(clipboard.nodes.size, added.size, added.toString())
  }

  @Test
  fun `pasting twice from one clipboard makes two subtrees, not one silent failure`() {
    val copied =
      reducer.reduce(reducer.initial(document, "discover-grid"), UiBuilderEditorEvent.CopySelected)
    val once = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    val twice = reducer.reduce(once, UiBuilderEditorEvent.Paste)

    val first = assertIs<String>(once.selectedNodeId)
    val second = assertIs<String>(twice.selectedNodeId)
    assertTrue(first != second, "$first == $second")
    assertTrue(first in twice.document.nodes && second in twice.document.nodes)
  }

  @Test
  fun `a cut subtree is still pasteable, because the clipboard is detached`() {
    // The point of snapshotting rather than referencing by id: by paste time the source is gone.
    val initial = reducer.initial(document, "discover-grid")
    val cut = reducer.reduce(initial, UiBuilderEditorEvent.CutSelected)
    assertFalse("discover-grid" in cut.document.nodes)

    val pasted = reducer.reduce(cut, UiBuilderEditorEvent.Paste)
    val restored = assertIs<String>(pasted.selectedNodeId)
    assertEquals(
      document.nodes.getValue("discover-grid").componentId,
      pasted.document.nodes.getValue(restored).componentId,
    )
  }

  @Test
  fun `copying is not an undo step`() {
    // Undo after a copy has to undo the edit before it, not the copy. Copying touches the editor,
    // never the document, so it must not consume a sequence number or record an operation.
    val initial = reducer.initial(document, "discover-grid")
    val copied = reducer.reduce(initial, UiBuilderEditorEvent.CopySelected)

    assertEquals(initial.operationSequence, copied.operationSequence)
    assertEquals(initial.document.revision, copied.document.revision)
    assertEquals(initial.canUndo, copied.canUndo)
  }

  @Test
  fun `a rejected cut leaves the clipboard alone`() {
    // Otherwise the editor claims to hold a subtree the user can still see in the document.
    val root = document.roots.single()
    val initial = reducer.initial(document, root)
    val cut = reducer.reduce(initial, UiBuilderEditorEvent.CutSelected)

    assertFalse(reducer.canCutSelected(initial), "the single root should not be cuttable")
    assertNull(cut.clipboard)
    assertTrue(root in cut.document.nodes)
  }

  @Test
  fun `paste is offered on the clipboard's component, not the selection's`() {
    val copied =
      reducer.reduce(reducer.initial(document, "discover-grid"), UiBuilderEditorEvent.CopySelected)
    assertTrue(reducer.canPaste(copied))
    // With nothing copied there is nowhere to paste, so the affordance is off rather than failing.
    assertFalse(reducer.canPaste(reducer.initial(document, "discover-grid")))
  }

  @Test
  fun `arrow selection walks the flattened tree, the way the layers panel reads`() {
    val rows = reducer.treeRows(document)
    val initial = reducer.initial(document, rows.first().nodeId)

    val next =
      reducer.reduce(initial, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next))
    assertEquals(rows[1].nodeId, next.selectedNodeId)
    // Down then up is where you started; nothing else would be usable while holding a key.
    val back =
      reducer.reduce(next, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Previous))
    assertEquals(rows.first().nodeId, back.selectedNodeId)
  }

  @Test
  fun `selection steps that have nowhere to go stay put rather than wrapping`() {
    val rows = reducer.treeRows(document)
    val top = reducer.initial(document, rows.first().nodeId)
    val bottom = reducer.initial(document, rows.last().nodeId)

    assertEquals(
      rows.first().nodeId,
      reducer
        .reduce(top, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Previous))
        .selectedNodeId,
    )
    assertEquals(
      rows.last().nodeId,
      reducer
        .reduce(bottom, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next))
        .selectedNodeId,
    )
  }

  @Test
  fun `into a container selects its first child, and out selects the parent`() {
    val rows = reducer.treeRows(document)
    val parentRow = rows.first { row -> rows.any { it.parent?.nodeId == row.nodeId } }
    val firstChild = rows.first { it.parent?.nodeId == parentRow.nodeId }

    val into =
      reducer.reduce(
        reducer.initial(document, parentRow.nodeId),
        UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.FirstChild),
      )
    assertEquals(firstChild.nodeId, into.selectedNodeId)
    assertEquals(
      parentRow.nodeId,
      reducer
        .reduce(into, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Parent))
        .selectedNodeId,
    )
  }

  @Test
  fun `moving into a container never selects a sibling by mistake`() {
    // A leaf's next row is its sibling or its uncle, and "go into this" must select neither.
    val rows = reducer.treeRows(document)
    val leaf = rows.last { row -> rows.none { it.parent?.nodeId == row.nodeId } }
    val state = reducer.initial(document, leaf.nodeId)

    assertEquals(
      leaf.nodeId,
      reducer
        .reduce(state, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.FirstChild))
        .selectedNodeId,
    )
  }

  @Test
  fun `selection by keyboard is not an undo step`() {
    val initial = reducer.initial(document, reducer.treeRows(document).first().nodeId)
    val moved =
      reducer.reduce(initial, UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next))

    assertEquals(initial.operationSequence, moved.operationSequence)
    assertEquals(initial.document.revision, moved.document.revision)
  }

  @Test
  fun `a node reorders among its siblings from the keyboard, as dragging it would`() {
    val rows = reducer.treeRows(document)
    fun siblingsOf(parent: ParentSlot?, in_: List<EditorTreeRow>) =
      in_.filter { it.parent == parent }.map { it.nodeId }

    val sibling = rows.first { row -> siblingsOf(row.parent, rows).size > 1 && row.parent != null }
    val before = siblingsOf(sibling.parent, rows)
    val moved =
      reducer.reduce(
        reducer.initial(document, sibling.nodeId),
        UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.After),
      )
    val after = siblingsOf(sibling.parent, reducer.treeRows(moved.document))

    assertEquals(before.size, after.size)
    assertEquals(before.toSet(), after.toSet(), "reorder must not add or drop a sibling")
    assertTrue(before != after, after.toString())
    // Still selected after moving — losing the selection mid-reorder would stop a run of presses.
    assertEquals(sibling.nodeId, moved.selectedNodeId)
  }

  @Test
  fun `a text layer is named by what it says, and still shows its type`() {
    val rows = reducer.treeRows(document)
    val texts = rows.filter { it.componentId == "m3/text" }
    assertTrue(texts.isNotEmpty(), "fixture should contain text nodes")

    // Every text node in the fixture carries a required `text`, so none should fall back to "Text".
    texts.forEach { row ->
      assertTrue(row.named, "${row.nodeId} should be named by its content, got ${row.label}")
      assertEquals("Text", row.componentLabel)
    }
    // The point of the change: the panel stops repeating one word.
    assertTrue(texts.map { it.label }.toSet().size > 1, texts.map { it.label }.toString())
  }

  @Test
  fun `a component with nothing to say keeps its component name`() {
    val rows = reducer.treeRows(document)
    val containers = rows.filter {
      it.componentId == "layout/column" || it.componentId == "m3/card"
    }
    containers.forEach { row ->
      assertFalse(row.named, "${row.nodeId} has no free-text property to be named by")
      assertEquals(row.componentLabel, row.label)
    }
  }

  @Test
  fun `a layer name is one line, however much text the node holds`() {
    val texts = reducer.treeRows(document).filter { it.componentId == "m3/text" }
    texts.forEach { assertTrue(it.label.length <= 40, "${it.nodeId}: ${it.label.length}") }
  }

  @Test
  fun `toggling adds and removes without disturbing the rest of the selection`() {
    val rows = reducer.treeRows(document)
    val a = rows[0].nodeId
    val b = rows[1].nodeId
    val c = rows[2].nodeId

    val three =
      listOf(b, c).fold(reducer.initial(document, a)) { state, id ->
        reducer.reduce(state, UiBuilderEditorEvent.ToggleNode(id))
      }
    assertEquals(listOf(a, b, c), three.selection)
    // The last click is the anchor, which is what a range extends from next.
    assertEquals(c, three.selectedNodeId)

    val without = reducer.reduce(three, UiBuilderEditorEvent.ToggleNode(b))
    assertEquals(listOf(a, c), without.selection)
  }

  @Test
  fun `a plain select replaces the selection rather than growing it`() {
    val rows = reducer.treeRows(document)
    val two =
      reducer.reduce(
        reducer.initial(document, rows[0].nodeId),
        UiBuilderEditorEvent.ToggleNode(rows[1].nodeId),
      )
    val replaced = reducer.reduce(two, UiBuilderEditorEvent.SelectNode(rows[2].nodeId))

    assertEquals(listOf(rows[2].nodeId), replaced.selection)
  }

  @Test
  fun `a range covers everything between the anchor and the click, in tree order`() {
    val rows = reducer.treeRows(document)
    val extended =
      reducer.reduce(
        reducer.initial(document, rows[0].nodeId),
        UiBuilderEditorEvent.ExtendSelectionTo(rows[3].nodeId),
      )

    assertEquals(rows.take(4).map { it.nodeId }.toSet(), extended.selection.toSet())
    // The clicked node anchors the next extension.
    assertEquals(rows[3].nodeId, extended.selectedNodeId)
  }

  @Test
  fun `a range extends backwards too`() {
    val rows = reducer.treeRows(document)
    val back =
      reducer.reduce(
        reducer.initial(document, rows[3].nodeId),
        UiBuilderEditorEvent.ExtendSelectionTo(rows[1].nodeId),
      )

    assertEquals(rows.subList(1, 4).map { it.nodeId }.toSet(), back.selection.toSet())
  }

  @Test
  fun `deleting several nodes is one undo step`() {
    val rows = reducer.treeRows(document)
    val siblings =
      rows
        .groupBy { it.parent }
        .values
        .first { group -> group.size > 2 && group.first().parent != null }
    val two = siblings.take(2).map { it.nodeId }
    val state =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))
    val deleted = reducer.reduce(state, UiBuilderEditorEvent.DeleteSelected)

    two.forEach { assertFalse(it in deleted.document.nodes, it) }
    // One revision, not two: the whole selection goes in a single operation.
    assertEquals(document.revision + 1, deleted.document.revision)
  }

  @Test
  fun `a selected descendant is not deleted twice by its selected ancestor`() {
    val rows = reducer.treeRows(document)
    val parentRow = rows.first { row -> rows.any { it.parent?.nodeId == row.nodeId } }
    val child = rows.first { it.parent?.nodeId == parentRow.nodeId }
    val both =
      reducer.reduce(
        reducer.initial(document, parentRow.nodeId),
        UiBuilderEditorEvent.ToggleNode(child.nodeId),
      )

    // Whether this is deletable at all depends on the fixture's cardinality; what must hold is
    // that the child is never emitted as its own deletion, since the parent already removes it.
    val deleted = reducer.reduce(both, UiBuilderEditorEvent.DeleteSelected)
    if (parentRow.nodeId !in deleted.document.nodes) {
      assertFalse(child.nodeId in deleted.document.nodes)
      assertEquals(document.revision + 1, deleted.document.revision)
    }
  }

  @Test
  fun `a slot at its minimum refuses the whole delete rather than half of it`() {
    // Cumulative, not one-at-a-time: asking of each node separately says yes twice and the second
    // delete is rejected halfway through, leaving a partial delete nobody asked for.
    val rows = reducer.treeRows(document)
    val group =
      rows.groupBy { it.parent }.values.firstOrNull { it.size > 1 && it.first().parent != null }
        ?: return
    val all = group.map { it.nodeId }
    val everything =
      all.drop(1).fold(reducer.initial(document, all.first())) { state, id ->
        reducer.reduce(state, UiBuilderEditorEvent.ToggleNode(id))
      }

    // Emptying a slot entirely is only allowed when its minimum is zero, which the reducer knows;
    // whichever way it answers, the delete must be all or nothing rather than partial.
    val attempted = reducer.reduce(everything, UiBuilderEditorEvent.DeleteSelected)
    val survivors = all.count { it in attempted.document.nodes }
    assertTrue(
      survivors == 0 || survivors == all.size,
      "partial delete: $survivors of ${all.size} survived",
    )
    if (!reducer.canDeleteSelected(everything)) assertEquals(all.size, survivors)
  }

  @Test
  fun `copying a multi-node selection pastes all of it, in order`() {
    val rows = reducer.treeRows(document)
    val siblings =
      rows.groupBy { it.parent }.values.first { it.size > 2 && it.first().parent != null }
    val two = siblings.take(2).map { it.nodeId }
    val selected =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))
    val copied = reducer.reduce(selected, UiBuilderEditorEvent.CopySelected)

    assertEquals(two, assertIs<EditorClipboard>(copied.clipboard).rootNodeIds)

    val pasted = reducer.reduce(copied, UiBuilderEditorEvent.Paste)
    // Both arrive, and the whole paste is selected so it can be moved as the unit it arrived as.
    assertEquals(2, pasted.selection.size)
    pasted.selection.forEach { assertTrue(it in pasted.document.nodes, it) }
    // One operation for the batch, not one per root.
    assertEquals(document.revision + 1, pasted.document.revision)
  }

  @Test
  fun `copying an ancestor and its descendant does not put the subtree on the clipboard twice`() {
    val rows = reducer.treeRows(document)
    val parentRow = rows.first { row -> rows.any { it.parent?.nodeId == row.nodeId } }
    val child = rows.first { it.parent?.nodeId == parentRow.nodeId }
    val both =
      reducer.reduce(
        reducer.initial(document, parentRow.nodeId),
        UiBuilderEditorEvent.ToggleNode(child.nodeId),
      )
    val copied = reducer.reduce(both, UiBuilderEditorEvent.CopySelected)

    // The ancestor already carries the child; pasting both roots would duplicate it.
    assertEquals(listOf(parentRow.nodeId), assertIs<EditorClipboard>(copied.clipboard).rootNodeIds)
  }

  @Test
  fun `duplicating a multi-node selection copies all of it, beside each original`() {
    val rows = reducer.treeRows(document)
    val siblings =
      rows.groupBy { it.parent }.values.first { it.size > 2 && it.first().parent != null }
    val two = siblings.take(2).map { it.nodeId }
    val selected =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))
    val duplicated = reducer.reduce(selected, UiBuilderEditorEvent.DuplicateSelected)

    assertEquals(2, duplicated.selection.size)
    duplicated.selection.forEach { assertTrue(it in duplicated.document.nodes, it) }
    two.forEach { assertTrue(it in duplicated.document.nodes, "$it should survive") }
    // One operation for the batch.
    assertEquals(document.revision + 1, duplicated.document.revision)
  }

  @Test
  fun `editing one field changes every selected node that declares it`() {
    val texts = reducer.treeRows(document).filter { it.componentId == "m3/text" }.take(2)
    assertEquals(2, texts.size)
    val selected =
      reducer.reduce(
        reducer.initial(document, texts[0].nodeId),
        UiBuilderEditorEvent.ToggleNode(texts[1].nodeId),
      )
    val edited =
      reducer.reduce(
        selected,
        UiBuilderEditorEvent.CommitProperty(texts[1].nodeId, "style", "titleLarge"),
      )

    texts.forEach { row ->
      val style = edited.document.nodes.getValue(row.nodeId).properties["style"]
      assertEquals(
        "titleLarge",
        style?.jsonObject?.get("value")?.jsonPrimitive?.content,
        row.nodeId,
      )
    }
    // One operation for the batch, and the selection survives so the next edit still spans it.
    assertEquals(document.revision + 1, edited.document.revision)
    assertEquals(2, edited.selection.size)
  }

  @Test
  fun `a multi-selection offers only the properties every component declares`() {
    val rows = reducer.treeRows(document)
    val text = rows.first { it.componentId == "m3/text" }
    val other = rows.first { it.componentId != "m3/text" && it.parent != null }
    val mixedSelection =
      reducer.reduce(
        reducer.initial(document, text.nodeId),
        UiBuilderEditorEvent.ToggleNode(other.nodeId),
      )

    val names = reducer.propertyFields(mixedSelection).map { it.name }.toSet()
    val textOnly = reducer.propertyFields(reducer.initial(document, text.nodeId)).map { it.name }
    val otherOnly = reducer.propertyFields(reducer.initial(document, other.nodeId)).map { it.name }
    // Exactly the intersection: a property only one of them has would be shown as editing the
    // selection while editing part of it.
    assertEquals(textOnly.toSet() intersect otherOnly.toSet(), names)
  }

  @Test
  fun `a field whose nodes disagree reads as mixed and shows no value`() {
    val texts = reducer.treeRows(document).filter { it.componentId == "m3/text" }.take(2)
    val selected =
      reducer.reduce(
        reducer.initial(document, texts[0].nodeId),
        UiBuilderEditorEvent.ToggleNode(texts[1].nodeId),
      )
    // The fixture's texts carry different content, so `text` must not show one of them for both.
    val field = reducer.propertyFields(selected).first { it.name == "text" }

    assertTrue(field.mixed)
    assertEquals("", field.value)
    assertEquals(2, field.nodeCount)
  }

  @Test
  fun `a single selection is unchanged by any of this`() {
    val text = reducer.treeRows(document).first { it.componentId == "m3/text" }
    val fields = reducer.propertyFields(reducer.initial(document, text.nodeId))

    assertTrue(fields.isNotEmpty())
    fields.forEach {
      assertEquals(1, it.nodeCount)
      assertFalse(it.mixed)
    }
  }

  @Test
  fun `a state-bound property refuses a literal rather than corrupting the binding`() {
    // `search-input.value` is bound to the `searchQuery` variable in the fixture:
    // {"type": "state", "variable": "searchQuery"}. A commit that reused that encoded type would
    // emit {"type": "state", "value": "..."} — a state read naming no variable. It refuses instead.
    val bound = document.nodes.getValue("search-input").properties.getValue("value").jsonObject
    assertEquals("state", bound["type"]?.jsonPrimitive?.content)

    val edited =
      reducer.reduce(
        reducer.initial(document, "search-input"),
        UiBuilderEditorEvent.CommitProperty("search-input", "value", "hello"),
      )

    assertIs<CommandOutcome.Rejected>(edited.lastOutcome)
    assertEquals(bound, edited.document.nodes.getValue("search-input").properties["value"])
  }

  @Test
  fun `a bound property can be unbound and bound again`() {
    // `m3/filter-chip.selected` is declared ["boolean", "string"], so the catalog admits both a
    // literal and a state read there. The fixture binds it with `stateEquals`.
    val state = reducer.initial(document, "chip-crime")
    val bound = document.nodes.getValue("chip-crime").properties.getValue("selected").jsonObject
    val variable = bound.getValue("variable").jsonPrimitive.content

    // The inspector reports the binding rather than showing an empty value with no explanation.
    assertEquals(
      variable,
      reducer.propertyFields(state).first { it.name == "selected" }.boundVariable,
    )

    // A bound property refuses a typed literal, so without unbind a binding is a one-way door.
    val unbound =
      reducer.reduce(state, UiBuilderEditorEvent.UnbindProperty("chip-crime", "selected"))
    val literal = unbound.document.nodes.getValue("chip-crime").properties.getValue("selected")
    assertEquals("bool", literal.jsonObject["type"]?.jsonPrimitive?.content, literal.toString())
    assertNull(reducer.propertyFields(unbound).first { it.name == "selected" }.boundVariable)

    val rebound =
      reducer.reduce(
        unbound,
        UiBuilderEditorEvent.BindPropertyToState(
          "chip-crime",
          "selected",
          variable,
          equalsValue = "Crime",
        ),
      )
    val encoded = rebound.document.nodes.getValue("chip-crime").properties.getValue("selected")
    // A boolean property needs the comparison shape, which the reducer can be asked about up front.
    assertTrue(reducer.bindingNeedsComparison(unbound, "chip-crime", "selected"))
    assertEquals("stateEquals", encoded.jsonObject["type"]?.jsonPrimitive?.content)
    assertEquals(variable, encoded.jsonObject["variable"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a property whose catalog type has no room for a state read refuses the binding`() {
    // `m3/text.text` is declared `string`, and a state read is not a string. The catalog decides
    // where a binding is legal; this reducer only asks it.
    val variable = reducer.stateVariableNames(reducer.initial(document, "chip-crime")).first()
    val text = reducer.treeRows(document).first { it.componentId == "m3/text" }
    val attempted =
      reducer.reduce(
        reducer.initial(document, text.nodeId),
        UiBuilderEditorEvent.BindPropertyToState(text.nodeId, "text", variable),
      )

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(
      document.nodes.getValue(text.nodeId).properties["text"],
      attempted.document.nodes.getValue(text.nodeId).properties["text"],
    )
  }

  @Test
  fun `a property that must be a state read refuses to be unbound`() {
    // `m3/search-input-field.value` is declared `object`: a literal is not valid there at all, so
    // the binding is not something to undo. The refusal comes from the catalog, not from a rule
    // this reducer invented.
    val unbound =
      reducer.reduce(
        reducer.initial(document, "search-input"),
        UiBuilderEditorEvent.UnbindProperty("search-input", "value"),
      )

    assertIs<CommandOutcome.Rejected>(unbound.lastOutcome)
    assertEquals(
      document.nodes.getValue("search-input").properties["value"],
      unbound.document.nodes.getValue("search-input").properties["value"],
    )
  }

  @Test
  fun `binding to a variable the design does not declare is refused`() {
    // A property bound to an undeclared name reads as null at render time and generates nothing at
    // export, so the builder must not be able to produce one.
    val attempted =
      reducer.reduce(
        reducer.initial(document, "chip-crime"),
        UiBuilderEditorEvent.BindPropertyToState(
          "chip-crime",
          "selected",
          "nothingDeclaresThis",
          equalsValue = "Crime",
        ),
      )

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(
      document.nodes.getValue("chip-crime").properties["selected"],
      attempted.document.nodes.getValue("chip-crime").properties["selected"],
    )
  }

  @Test
  fun `wrapping puts the selection inside a new container, in one step`() {
    val rows = reducer.treeRows(document)
    val siblings =
      rows.groupBy { it.parent }.values.first { it.size > 2 && it.first().parent != null }
    val two = siblings.take(2).map { it.nodeId }
    val selected =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))

    val candidate = reducer.wrapCandidates(selected).firstOrNull() ?: return
    val wrapped =
      reducer.reduce(selected, UiBuilderEditorEvent.WrapSelection(candidate.componentId))
    val containerId = assertIs<String>(wrapped.selectedNodeId)

    // Both wrapped nodes now sit under the container rather than beside it.
    two.forEach { nodeId ->
      assertEquals(
        containerId,
        reducer.treeRows(wrapped.document).first { it.nodeId == nodeId }.parent?.nodeId,
        nodeId,
      )
    }
    // One undo step: a container inserted with its children still outside is not a state the
    // document should rest in.
    assertEquals(document.revision + 1, wrapped.document.revision)
  }

  @Test
  fun `wrapping keeps the selection's place in its parent`() {
    val rows = reducer.treeRows(document)
    val siblings =
      rows.groupBy { it.parent }.values.first { it.size > 2 && it.first().parent != null }
    val parent = requireNotNull(siblings.first().parent)
    // Wrap the *last* two so that appearing at the end would be indistinguishable from correct.
    val two = siblings.takeLast(2).map { it.nodeId }
    val before = siblings.map { it.nodeId }
    val selected =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))
    val candidate = reducer.wrapCandidates(selected).firstOrNull() ?: return
    val wrapped =
      reducer.reduce(selected, UiBuilderEditorEvent.WrapSelection(candidate.componentId))

    val after = reducer.treeRows(wrapped.document).filter { it.parent == parent }.map { it.nodeId }
    // The container stands where the wrapped nodes stood: the untouched leading siblings first.
    assertEquals(before.dropLast(2), after.dropLast(1))
  }

  @Test
  fun `a selection spread across two parents cannot be wrapped`() {
    // The container can only live in one place, so the other nodes would have to move somewhere
    // the document never put them. Refusing beats picking a parent for the user.
    //
    // Leaves, and from two different parents: a node selected alongside its own ancestor is
    // reduced away by `selectionRoots`, which would leave one node with one parent and wrap fine.
    val rows = reducer.treeRows(document)
    val leaves = rows.filter { row ->
      row.parent != null && rows.none { it.parent?.nodeId == row.nodeId }
    }
    val first = leaves.first()
    val elsewhere = leaves.first { it.parent != first.parent }
    val selected =
      reducer.reduce(
        reducer.initial(document, first.nodeId),
        UiBuilderEditorEvent.ToggleNode(elsewhere.nodeId),
      )
    assertEquals(2, selected.selection.size, "both leaves should survive selectionRoots")

    assertEquals(emptyList(), reducer.wrapCandidates(selected))
    val attempted = reducer.reduce(selected, UiBuilderEditorEvent.WrapSelection("layout/column"))
    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(document.revision, attempted.document.revision)
  }

  @Test
  fun `only containers whose slot accepts the whole selection are offered`() {
    val rows = reducer.treeRows(document)
    val text = rows.first { it.componentId == "m3/text" && it.parent != null }
    val candidates = reducer.wrapCandidates(reducer.initial(document, text.nodeId))

    // Every offer has to actually work — the menu is a promise, not a guess.
    candidates.forEach { candidate ->
      val wrapped =
        reducer.reduce(
          reducer.initial(document, text.nodeId),
          UiBuilderEditorEvent.WrapSelection(candidate.componentId),
        )
      assertTrue(
        wrapped.lastOutcome !is CommandOutcome.Rejected,
        "${candidate.componentId}: ${wrapped.lastOutcome}",
      )
    }
  }

  @Test
  fun `wrapping and unwrapping returns the document to where it started`() {
    // Wrap without unwrap is a one-way door, and worse than for a binding: a container added by
    // mistake cannot be deleted either, because deleting it takes the children with it.
    val rows = reducer.treeRows(document)
    val siblings =
      rows.groupBy { it.parent }.values.first { it.size > 2 && it.first().parent != null }
    val parent = requireNotNull(siblings.first().parent)
    val before = rows.filter { it.parent == parent }.map { it.nodeId }
    val two = siblings.take(2).map { it.nodeId }
    val selected =
      reducer.reduce(reducer.initial(document, two[0]), UiBuilderEditorEvent.ToggleNode(two[1]))
    val candidate = reducer.wrapCandidates(selected).firstOrNull() ?: return

    val wrapped =
      reducer.reduce(selected, UiBuilderEditorEvent.WrapSelection(candidate.componentId))
    val unwrapped = reducer.reduce(wrapped, UiBuilderEditorEvent.UnwrapSelection)

    val after =
      reducer.treeRows(unwrapped.document).filter { it.parent == parent }.map { it.nodeId }
    assertEquals(before, after)
    // The container is gone rather than left behind empty.
    assertFalse(wrapped.selectedNodeId!! in unwrapped.document.nodes)
  }

  @Test
  fun `unwrapping keeps the children rather than deleting them with their container`() {
    // Deleting the container first would take its subtree; the move-then-delete order is the whole
    // correctness of this operation.
    val rows = reducer.treeRows(document)
    val container = rows.first { row ->
      rows.count { it.parent?.nodeId == row.nodeId } > 1 && row.parent != null
    }
    val children = rows.filter { it.parent?.nodeId == container.nodeId }.map { it.nodeId }
    val state = reducer.initial(document, container.nodeId)
    if (!reducer.canUnwrapSelected(state)) return

    val unwrapped = reducer.reduce(state, UiBuilderEditorEvent.UnwrapSelection)

    children.forEach {
      assertTrue(it in unwrapped.document.nodes, "$it was deleted with its parent")
    }
    assertFalse(container.nodeId in unwrapped.document.nodes)
    // One undo step for the whole operation.
    assertEquals(document.revision + 1, unwrapped.document.revision)
  }

  @Test
  fun `a node with no children cannot be unwrapped`() {
    val rows = reducer.treeRows(document)
    val leaf = rows.first { row ->
      row.parent != null && rows.none { it.parent?.nodeId == row.nodeId }
    }

    assertFalse(reducer.canUnwrapSelected(reducer.initial(document, leaf.nodeId)))
  }

  @Test
  fun `unwrap is offered only where the parent would accept the children`() {
    val rows = reducer.treeRows(document)
    rows
      .filter { it.parent != null }
      .forEach { row ->
        val state = reducer.initial(document, row.nodeId)
        if (reducer.canUnwrapSelected(state)) {
          val unwrapped = reducer.reduce(state, UiBuilderEditorEvent.UnwrapSelection)
          assertTrue(
            unwrapped.lastOutcome !is CommandOutcome.Rejected,
            "${row.nodeId}: ${unwrapped.lastOutcome}",
          )
        }
      }
  }

  @Test
  fun `a state name that becomes a Kotlin keyword is refused at creation`() {
    // `identifier()` does not escape, so this would emit `var when: Boolean`. The wire cannot
    // rename a variable later, so creation is the only moment a design can be stopped from holding
    // a name it can never generate.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        blankUiBuilderDocument(
          "from-scratch",
          document.catalogPin,
          document.environment,
          listOf(NewDesignState("when", NewDesignStateType.Flag, JsonPrimitive(false))),
        )
      }

    assertTrue(failure.message.orEmpty().contains("keyword"), failure.message.orEmpty())
  }

  @Test
  fun `two state names that export to one identifier are refused at creation`() {
    // `identifier()` drops separators, so both of these become `fooBar` and declare the same
    // variable twice. Distinct on the wire, identical in the generated Kotlin.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        blankUiBuilderDocument(
          "from-scratch",
          document.catalogPin,
          document.environment,
          listOf(
            NewDesignState("foo-bar", NewDesignStateType.Text, JsonPrimitive("a")),
            NewDesignState("foo_bar", NewDesignStateType.Text, JsonPrimitive("b")),
          ),
        )
      }

    assertTrue(
      failure.message.orEmpty().contains("distinct once exported"),
      failure.message.orEmpty(),
    )
  }

  @Test
  fun `a from-scratch design can declare state and bind a property to it`() {
    // The whole point of declaring at creation: without it a new design has no state, nothing can
    // add one, and `BindPropertyToState` has nothing to offer. This is the end-to-end path.
    val blank =
      blankUiBuilderDocument(
        "from-scratch",
        document.catalogPin,
        document.environment,
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false))),
      )
    val state = reducer.initial(blank, blank.roots.single())

    assertEquals(listOf("expanded"), reducer.stateVariableNames(state))

    // Put a chip in the screen, then bind its boolean `selected` to the declared flag.
    val target = requireNotNull(reducer.dropTarget(state, "m3/filter-chip"))
    val inserted =
      reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/filter-chip", target))
    val chipId = assertIs<String>(inserted.selectedNodeId)
    val bound =
      reducer.reduce(
        inserted,
        UiBuilderEditorEvent.BindPropertyToState(
          chipId,
          "selected",
          "expanded",
          equalsValue = "true",
        ),
      )

    val encoded = bound.document.nodes.getValue(chipId).properties.getValue("selected").jsonObject
    assertEquals("stateEquals", encoded["type"]?.jsonPrimitive?.content, encoded.toString())
    assertEquals("expanded", encoded["variable"]?.jsonPrimitive?.content)
  }

  @Test
  fun `a component can be inserted already wired to toggle a flag`() {
    // Completes the from-scratch story: declare state, bind something to read it, insert a control
    // that writes it. Insertion is the only moment the wire lets a client attach an event binding.
    val blank =
      blankUiBuilderDocument(
        "from-scratch",
        document.catalogPin,
        document.environment,
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false))),
      )
    val state = reducer.initial(blank, blank.roots.single())
    val target = requireNotNull(reducer.dropTarget(state, "m3/button"))

    val inserted =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponentWithAction(
          "m3/button",
          target,
          EditorStateAction.Toggle("expanded"),
        ),
      )
    val buttonId = assertIs<String>(inserted.selectedNodeId)
    val bindings = inserted.document.nodes.getValue(buttonId).eventBindings

    // `click`, and only click: it is the one event this renderer applies to any node.
    assertEquals(setOf("click"), bindings.keys)
    val action = bindings.getValue("click").jsonArray.single().jsonObject
    assertEquals("toggle", action.getValue("type").jsonPrimitive.content)
    assertEquals("expanded", action.getValue("variable").jsonPrimitive.content)
  }

  @Test
  fun `the action the editor writes is one the renderer actually performs`() {
    // The two halves have to agree, or the builder draws a button that does nothing when pressed.
    val blank =
      blankUiBuilderDocument(
        "from-scratch",
        document.catalogPin,
        document.environment,
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false))),
      )
    val state = reducer.initial(blank, blank.roots.single())
    val target = requireNotNull(reducer.dropTarget(state, "m3/button"))
    val inserted =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponentWithAction(
          "m3/button",
          target,
          EditorStateAction.Toggle("expanded"),
        ),
      )
    val buttonId = assertIs<String>(inserted.selectedNodeId)
    val action =
      inserted.document.nodes.getValue(buttonId).eventBindings.getValue("click").jsonArray.single()

    assertEquals(
      "expanded" to "true",
      uiBuilderStateWrite(action.jsonObject, mapOf("expanded" to "false")),
    )
  }

  @Test
  fun `wiring a control to a variable the design does not declare is refused`() {
    val blank = blankUiBuilderDocument("from-scratch", document.catalogPin, document.environment)
    val state = reducer.initial(blank, blank.roots.single())
    val target = requireNotNull(reducer.dropTarget(state, "m3/button"))

    val attempted =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponentWithAction(
          "m3/button",
          target,
          EditorStateAction.Toggle("expanded"),
        ),
      )

    assertIs<CommandOutcome.Rejected>(attempted.lastOutcome)
    assertEquals(blank.nodes.keys, attempted.document.nodes.keys)
  }

  @Test
  fun `wiring is offered only when the design has state to write`() {
    val without = blankUiBuilderDocument("a", document.catalogPin, document.environment)
    val with =
      blankUiBuilderDocument(
        "b",
        document.catalogPin,
        document.environment,
        listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false))),
      )

    assertEquals(
      emptyList(),
      reducer.actionInsertCandidates(reducer.initial(without, without.roots.single())),
    )
    assertTrue(
      reducer.actionInsertCandidates(reducer.initial(with, with.roots.single())).isNotEmpty()
    )
  }
}
