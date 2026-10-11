package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcRemark
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.uibuilder.canvas.UiBuilderBoard
import ee.schimke.composeai.uibuilder.canvas.boardItemCount
import ee.schimke.composeai.uibuilder.canvas.boardRootId
import ee.schimke.composeai.uibuilder.canvas.isBoard
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorVariantAxis
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A design holding several top-level items keeps them in a board, which is a node. Rules (from
 * `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`): one root, the arrangement is an ordinary
 * editable `layout/column`, and the wrap plus its item are one command so they undo together.
 */
class BoardInsertTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `an add beside wraps the existing root in a board and stays at one root`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val originalRoot = initial.document.roots.single()

    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    assertIs<CommandOutcome.Accepted>(added.lastOutcome, added.lastOutcome.toString())
    // The whole point: a design of several items is one tree, not several roots. Every consumer
    // downstream — both exporters, the projection, the export gate — sees what it always saw.
    val boardId = added.document.roots.single()
    val board = added.document.nodes.getValue(boardId)
    assertEquals(UiBuilderBoard.COMPONENT_ID, board.componentId)
    assertEquals(
      listOf(originalRoot, added.selectedNodeId),
      board.slots.getValue(UiBuilderBoard.SLOT),
    )
  }

  /** The arrangement is in the document, so "make the gap smaller" is an ordinary property edit. */
  @Test
  fun `the board carries its spacing and alignment as editable properties`() {
    val added =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    val board = added.document.nodes.getValue(added.document.roots.single())
    assertEquals(
      UiBuilderBoard.SPACING_DP.toString(),
      board.properties["verticalSpacingDp"]?.jsonObject?.get("value")?.toString()?.trim('"'),
    )
    assertEquals(
      "center",
      board.properties["horizontalAlignment"]?.jsonObject?.get("value")?.toString()?.trim('"'),
    )
  }

  @Test
  fun `a second add beside appends to the board rather than nesting another one`() {
    val first =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )
    val boardId = first.document.roots.single()

    val second = reducer.reduce(first, UiBuilderEditorEvent.InsertComponentBeside("m3/text"))

    assertIs<CommandOutcome.Accepted>(second.lastOutcome, second.lastOutcome.toString())
    assertEquals(boardId, second.document.roots.single())
    assertEquals(
      3,
      second.document.nodes.getValue(boardId).slots.getValue(UiBuilderBoard.SLOT).size,
    )
    assertEquals(3, second.document.boardItemCount)
  }

  /** One command, so one undo: the board disappears when the Add that created it is undone. */
  @Test
  fun `undo takes back the wrap and the item together`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    val undone = reducer.reduce(added, UiBuilderEditorEvent.Undo)

    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, undone.lastOutcome.toString())
    assertEquals(initial.document.roots, undone.document.roots)
    assertEquals(initial.document.nodes.keys, undone.document.nodes.keys)
  }

  /** A board with one item is just a screen in a column, not "a board of several items". */
  @Test
  fun `one item is not yet a board`() {
    val added =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    assertNotNull(added.document.boardRootId)
    assertTrue(added.document.isBoard)
    assertEquals(2, added.document.boardItemCount)
    // The design it came from: one root, no board, nothing claimed.
    assertNull(document.boardRootId)
    assertEquals(0, document.boardItemCount)
  }

  /**
   * An empty design gets no board: the component becomes the root. Record-free emitters route on
   * the root id, so a Wear screen as a board item would stop being a Wear screen.
   */
  @Test
  fun `an empty design takes the component as its root rather than a board`() {
    val empty = document.copy(roots = emptyList(), nodes = emptyMap())

    val added =
      reducer.reduce(
        reducer.initial(empty, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )

    assertIs<CommandOutcome.Accepted>(added.lastOutcome, added.lastOutcome.toString())
    val root = added.document.nodes.getValue(added.document.roots.single())
    assertEquals("m3/card", root.componentId)
    assertNull(added.document.boardRootId)
  }

  /**
   * The theme lives on a root `m3/surface`; lookups must still find it after a themed screen is
   * wrapped in a board.
   */
  @Test
  fun `wrapping a themed screen keeps its theme`() {
    val rootId = document.roots.single()
    val root = document.nodes.getValue(rootId)
    assertEquals("m3/surface", root.componentId)
    // A theme worth losing: the fixture's own surface carries none, so comparing it before and
    // after would pass against the bug it is here to catch.
    val themed =
      document.copy(
        nodes =
          document.nodes +
            (rootId to
              root.copy(
                properties =
                  JsonObject(
                    root.properties +
                      mapOf(
                        "themeTypeScale" to
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("float"),
                              "value" to JsonPrimitive(1.25),
                            )
                          )
                      )
                  )
              ))
      )
    val initial = reducer.initial(themed, selectedNodeId = null)
    assertEquals(1.25f, reducer.themeSettings(initial).typeScale)

    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    assertEquals(
      rootId,
      added.document.nodes
        .getValue(added.document.roots.single())
        .slots
        .getValue(UiBuilderBoard.SLOT)
        .first(),
    )
    assertEquals(1.25f, reducer.themeSettings(added).typeScale)
  }

  /**
   * Every accepted edit and collaborator delta rebuilds the editor from the authoritative document,
   * so tool modes (Add beside) must carry across.
   */
  @Test
  fun `the tool modes survive a document arriving`() {
    val state =
      reducer
        .initial(document, selectedNodeId = null)
        .copy(addBeside = true, variantAxes = setOf(EditorVariantAxis.Dark))

    val reconciled = reducer.reconciled(state, document)

    assertTrue(reconciled.addBeside)
    assertEquals(setOf(EditorVariantAxis.Dark), reconciled.variantAxes)
  }

  /**
   * A root-only component (a Wear scaffold) must also be refused as an item of an existing board;
   * `RecordFreeExport` routes on the root id.
   */
  @Test
  fun `a component its emitter wants at the root is refused even onto an existing board`() {
    val onBoard =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )
    assertTrue(onBoard.document.isBoard)

    RecordFreeExport.ROOT_ONLY_COMPONENT_IDS.forEach { rootOnly ->
      assertNotNull(reducer.besideRefusal(onBoard, rootOnly), rootOnly)
    }
    // But not on an empty design: the component lands at the root, where its emitter wants it.
    val empty = reducer.initial(document.copy(roots = emptyList(), nodes = emptyMap()))
    RecordFreeExport.ROOT_ONLY_COMPONENT_IDS.forEach { rootOnly ->
      assertNull(reducer.besideRefusal(empty, rootOnly), rootOnly)
    }
    // The document-level refusal has nothing to say here, which is exactly why the component-level
    // one had to exist.
    assertNull(reducer.besideRefusal(onBoard))
    assertNull(reducer.besideRefusal(onBoard, "m3/card"))
  }

  /**
   * `besideRefusal(state, componentId)` checks the component, then falls through to the document,
   * so on a not-yet-board Wear screen it returns the wrap refusal for every id. Callers wanting a
   * per-component answer (e.g. the insert panel) must ask `besideRefusal(state)` first.
   */
  @Test
  fun `a component refusal falls through to the document's, so a per-component caller must ask both`() {
    val wearScreen =
      reducer.initial(
        document.copy(
          roots = listOf("screen"),
          nodes =
            mapOf(
              "screen" to
                UiBuilderNode(id = "screen", componentId = WearScreenCodeExporter.SCAFFOLD)
            ),
        )
      )
    assertFalse(wearScreen.document.isBoard)

    val documentRefusal = reducer.besideRefusal(wearScreen)
    assertNotNull(documentRefusal)
    // An ordinary component with nothing wrong with it gets the document's answer, not its own.
    assertEquals(documentRefusal, reducer.besideRefusal(wearScreen, "m3/card"))
    assertEquals(documentRefusal, reducer.besideRefusal(wearScreen, "m3/text"))
  }

  /**
   * Played Remote Compose document rows under Add beside must place beside, not resolve an ordinary
   * drop target.
   */
  @Test
  fun `a remote compose document can be added beside the design`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val source = RemoteComposeSource("appcard__ideal__default__compact", "App card", "appcard")
    val encoded =
      Base64.Default.encode(
        RcDocumentCodec.encode(
          RcDocument(
            header = RcHeader(RcVersion(0, 1, 0)),
            operations = listOf(RcRemark("published sticker")),
          )
        )
      )

    val added =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.InsertRemoteComposeDocumentBeside(source, encoded),
      )

    assertIs<CommandOutcome.Accepted>(added.lastOutcome, added.lastOutcome.toString())
    val board = added.document.nodes.getValue(added.document.roots.single())
    assertEquals(UiBuilderBoard.COMPONENT_ID, board.componentId)
    val item = added.document.nodes.getValue(board.slots.getValue(UiBuilderBoard.SLOT).last())
    assertEquals(REMOTE_COMPOSE_DOCUMENT_COMPONENT_ID, item.componentId)
    // The bytes land on the document node, not on the board that was inserted before it.
    assertEquals(
      encoded,
      item.properties.getValue("documentBase64").jsonObject.getValue("value").jsonPrimitive.content,
    )
  }

  /**
   * The refusal must hold on every path in, including plain Add and dragging onto the board via
   * `dropTarget` (a column's `children` accepts the `Scaffold` role).
   */
  @Test
  fun `a root-only component has no slot destination at all`() {
    val onBoard =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = null),
        UiBuilderEditorEvent.InsertComponentBeside("m3/card"),
      )
    val boardId = assertNotNull(onBoard.document.boardRootId)
    val selected = reducer.initial(onBoard.document, selectedNodeId = boardId)

    RecordFreeExport.ROOT_ONLY_COMPONENT_IDS.forEach { rootOnly ->
      assertNull(reducer.dropTarget(selected, rootOnly), rootOnly)
    }
    // The board still takes everything else, so this refuses the component rather than the board.
    assertNotNull(reducer.dropTarget(selected, "m3/text"))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
