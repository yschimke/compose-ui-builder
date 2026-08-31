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
    assertEquals(100, inserted.document.revision)
    assertEquals(insertedId, inserted.selectedNodeId)
    assertEquals(
      insertedId,
      inserted.document.nodes.getValue("discover-grid").slots.getValue("items").last(),
    )

    val edited = reducer.reduce(inserted, UiBuilderEditorEvent.SetText(insertedId, "From canvas"))
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    assertEquals(101, edited.document.revision)
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
    assertEquals(102, moved.document.revision)
    assertEquals(insertedId, children[children.lastIndex - 1])
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
  fun `required search input subtree is capability valid and committed atomically`() {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/search-bar"))
    val inserted =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertComponent("m3/search-bar", target),
      )

    assertTrue(inserted.lastOutcome is CommandOutcome.Accepted, inserted.lastOutcome.toString())
    assertEquals(101, inserted.document.nodes.size)
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

    assertEquals(99, rows.size)
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
