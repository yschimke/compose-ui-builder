package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated `remote-layouts-v1.json` answers what the emitter used to keep by hand, and still
 * names every argument the emitter writes, so a release that renames one fails here before the
 * compile lane.
 */
class RemoteLayoutSignaturesTest {
  /** Each container `RemoteContentEmitter.container` writes, with every argument it can write. */
  private val written =
    mapOf(
      "RemoteBox" to listOf("modifier", "contentAlignment"),
      "RemoteColumn" to listOf("modifier", "verticalArrangement", "horizontalAlignment"),
      "RemoteRow" to listOf("modifier", "horizontalArrangement", "verticalAlignment"),
      "RemoteFitBox" to listOf("modifier", "horizontalAlignment", "verticalArrangement"),
      "RemoteFlowRow" to
        listOf("modifier", "horizontalArrangement", "verticalArrangement", "maxItemsInEachRow"),
      "RemoteCollapsibleColumn" to listOf("modifier", "verticalArrangement", "horizontalAlignment"),
      "RemoteCollapsibleRow" to listOf("modifier", "horizontalArrangement", "verticalAlignment"),
    )

  @Test
  fun `every container the emitter writes is recorded`() {
    assertEquals(written.keys, RemoteLayoutSignatures.layouts.keys)
  }

  @Test
  fun `every argument the emitter writes is a released parameter of a content overload`() {
    written.forEach { (symbol, arguments) ->
      arguments.forEach { argument ->
        assertTrue(
          RemoteLayoutSignatures.acceptsWithContent(symbol, listOf(argument)),
          "$symbol has no overload taking `$argument` and content " +
            "(${RemoteLayoutSignatures.version})",
        )
      }
      assertTrue(RemoteLayoutSignatures.acceptsWithContent(symbol, arguments), symbol)
    }
  }

  @Test
  fun `only the spacer box and the fit box may be written without content`() {
    assertTrue(RemoteLayoutSignatures.contentLambdaOptional("RemoteBox", emptyList()))
    assertTrue(RemoteLayoutSignatures.contentLambdaOptional("RemoteBox", listOf("modifier")))
    assertFalse(
      RemoteLayoutSignatures.contentLambdaOptional("RemoteBox", listOf("contentAlignment")),
    )
    assertFalse(
      RemoteLayoutSignatures.contentLambdaOptional(
        "RemoteBox",
        listOf("modifier", "contentAlignment"),
      ),
    )
    assertTrue(
      RemoteLayoutSignatures.contentLambdaOptional(
        "RemoteFitBox",
        listOf("modifier", "verticalArrangement"),
      ),
    )
    listOf(
        "RemoteColumn",
        "RemoteRow",
        "RemoteFlowRow",
        "RemoteCollapsibleColumn",
        "RemoteCollapsibleRow",
      )
      .forEach { assertFalse(RemoteLayoutSignatures.contentLambdaOptional(it, emptyList()), it) }
  }

  @Test
  fun `an unrecorded container writes the lambda`() {
    assertFalse(RemoteLayoutSignatures.contentLambdaOptional("RemoteSomethingNew", emptyList()))
  }
}
