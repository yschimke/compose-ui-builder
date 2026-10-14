package ee.schimke.composeai.uibuilder

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.uibuilder.export.NativeNodeTags
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native lane's Remote node bounds, end to end on the reading side: a played document whose
 * components carry [NativeNodeTags] content descriptions comes back as Compose semantics nodes with
 * each component's own bounds, and [NativeNodeTags.nodeIds] recovers the ids from them.
 */
@OptIn(ExperimentalTestApi::class)
class NativeNodeTagBoundsTest {

  @Test
  fun `tagged remote components report their ids and bounds through the player`() =
    runDesktopComposeUiTest(width = 100, height = 100) {
      setContent { RcComposePlayer(document()) }

      val bounds =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
          )
          .fetchSemanticsNodes()
          .flatMap { node ->
            val descriptions =
              node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val rect = node.boundsInRoot
            NativeNodeTags.nodeIds(descriptions).map { id ->
              id to listOf(rect.left, rect.top, rect.right, rect.bottom).map { it.roundToInt() }
            }
          }
          .toMap()

      assertEquals(
        mapOf(
          "column" to listOf(0, 0, 60, 50),
          "a" to listOf(0, 0, 40, 10),
          "b" to listOf(0, 10, 30, 30),
          "c" to listOf(0, 30, 20, 40),
          "d" to listOf(0, 40, 10, 50),
        ),
        bounds,
      )
    }

  /**
   * Why the emitter writes the tag *first* in a node's chain. The player applies a component's
   * semantics operations in document order, and Compose collapses one layout node's semantics so
   * the outermost modifier's `contentDescription` wins and a `clearAndSetSemantics` drops what is
   * inside it. Ahead of the node's own semantics the tag survives both; behind them it is lost.
   */
  @Test
  fun `a tag ahead of a node's own semantics survives them, and one behind does not`() {
    assertEquals(listOf("c", "d"), playedIds(tagFirst = true).filter { it in setOf("c", "d") })
    assertEquals(emptyList(), playedIds(tagFirst = false).filter { it in setOf("c", "d") })
  }

  private fun playedIds(tagFirst: Boolean): List<String> {
    var ids = emptyList<String>()
    runDesktopComposeUiTest(width = 100, height = 100) {
      setContent { RcComposePlayer(document(tagFirst)) }
      ids =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
          )
          .fetchSemanticsNodes()
          .flatMap { NativeNodeTags.nodeIds(it.config[SemanticsProperties.ContentDescription]) }
    }
    return ids
  }

  /**
   * A column 60x50 holding canvases of 40x10, 30x20, 20x10 and 10x10. `c` sets its own `Play`
   * description and `d` clears and sets its semantics; [tagFirst] puts their tags ahead of those,
   * as the emitter does, or behind them.
   */
  private fun document(tagFirst: Boolean = true): RcDocument {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    fun width(px: Float) = RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(px))
    fun height(px: Float) = RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(px))
    fun semantics(textId: Int, mode: Int = RcAccessibilitySemantics.MODE_SET) =
      RcAccessibilitySemantics(
        contentDescriptionId = textId,
        role = RcAccessibilitySemantics.ROLE_UNKNOWN,
        textId = 0,
        stateDescriptionId = 0,
        mode = mode,
        enabled = true,
        clickable = false,
      )
    return RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = 100, legacyHeight = 100, modern = false),
      listOf(
        RcTextData(10, NativeNodeTags.tag("column")),
        RcTextData(11, NativeNodeTags.tag("a")),
        RcTextData(12, NativeNodeTags.tag("b")),
        RcTextData(13, NativeNodeTags.tag("c")),
        RcTextData(14, "Play"),
        RcTextData(15, NativeNodeTags.tag("d")),
        RcRootLayout(1),
        RcLayoutContent(2),
        RcColumnLayout(3, 30, 1, 4, RcFloatWord.literal(0f)),
        width(60f),
        height(50f),
        semantics(10),
        RcLayoutContent(4),
        RcCanvasLayout(5, 50),
        width(40f),
        height(10f),
        semantics(11),
        end,
        RcCanvasLayout(6, 60),
        width(30f),
        height(20f),
        semantics(12),
        end,
        RcCanvasLayout(7, 70),
        width(20f),
        height(10f),
        *ordered(tagFirst, semantics(13), semantics(14)),
        end,
        RcCanvasLayout(8, 80),
        width(10f),
        height(10f),
        *ordered(
          tagFirst,
          semantics(15),
          semantics(14, RcAccessibilitySemantics.MODE_CLEAR_AND_SET),
        ),
        end,
        end,
        end,
        end,
        end,
      ),
    )
  }

  private fun ordered(tagFirst: Boolean, tag: RcOperation, own: RcOperation) =
    if (tagFirst) arrayOf(tag, own) else arrayOf(own, tag)
}
