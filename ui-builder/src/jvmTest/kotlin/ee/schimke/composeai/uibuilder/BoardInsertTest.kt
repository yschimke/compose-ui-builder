package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcRemark
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * A design that holds several top-level items keeps them in a board, and a board is a node.
 *
 * The rules under test are stated once in `docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`: the
 * root list stays at one, the arrangement is an ordinary `layout/column` the inspector can edit,
 * and the wrap and the item that motivated it are one command so they undo together.
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

  /**
   * The arrangement is in the document, which is the difference between a board node and a
   * synthetic one: "make the gap smaller" is an ordinary property edit rather than a constant in
   * four consumers.
   */
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

  /**
   * One command, so one undo. A board that appeared because of an Add disappears when that Add is
   * taken back — the alternative is a wrapper left behind by an edit the author reversed.
   */
  @Test
  fun `undo takes back the wrap and the item together`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val added = reducer.reduce(initial, UiBuilderEditorEvent.InsertComponentBeside("m3/card"))

    val undone = reducer.reduce(added, UiBuilderEditorEvent.Undo)

    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, undone.lastOutcome.toString())
    assertEquals(initial.document.roots, undone.document.roots)
    assertEquals(initial.document.nodes.keys, undone.document.nodes.keys)
  }

  /**
   * A board with one item is a screen that happens to sit in a column. Claiming otherwise would put
   * "this is a board of several items" on a design showing one.
   */
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
   * An empty design has nothing to be beside, so it gets no board — the component becomes the root,
   * exactly as an ordinary Add would place it.
   *
   * Not a tidiness point. Both record-free emitters route on the *root* component id, so a Wear
   * screen added as the first item of a board would quietly stop being a Wear screen; the wrap is
   * refused for an existing Wear root for that reason, and this is the case with no root to refuse
   * over.
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
   * The theme lives on a root `m3/surface`, and both lookups for it scanned the root list. Wrapping
   * a themed screen therefore dropped its palette, type scale and corner radius from the canvas and
   * made Apply theme refuse the document for having no root surface.
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
   * Every accepted edit and every collaborator delta rebuilds the editor from the authoritative
   * document, so a tool mode not carried across is lost on the next keystroke anyone makes: the
   * strip emptied and Add beside turned itself off one Add after being switched on.
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
   * The wrap refusal is about the *root*, so a board that already exists has no wrap left to refuse
   * — which is how a Wear scaffold could have become one item of a board on a design whose first
   * Add was an ordinary layout. `RecordFreeExport` routes on the root component id, so that design
   * would have lost its emitter and its native preview lane without saying so.
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
    // But not on an empty design, which has no board to become an item of: the component lands at
    // the root, which is where its emitter wants it. Refusing there refused the one placement that
    // was already correct.
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
   * A played Remote Compose document is exactly the kind of asset a board holds, and the panel
   * offered every such row under Add beside — then resolved an ordinary drop target anyway, so the
   * row either refused after its fetch or landed inside the selection.
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
   * The refusal has to hold on every path in, not just the Add beside one: turning the switch off
   * and pressing Add, or dragging the scaffold onto the board, reached the same placement through
   * `dropTarget` — a column's `children` slot accepts the `Scaffold` role, so nothing else was
   * going to refuse it.
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
