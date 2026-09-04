package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What the renderer makes of one authored modifier.
 *
 * Tested apart from the renderer for the same reason the state transition is: the reading is pure,
 * and a rule about what a document's layout chain means should not need a frame to verify.
 */
class UiBuilderModifierPlanTest {
  private fun modifier(json: String) = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `every modifier the document declares is read`() {
    assertEquals(
      UiBuilderModifierPlan.FillMaxSize,
      uiBuilderModifier(modifier("""{"type":"fillMaxSize"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.FillMaxWidth,
      uiBuilderModifier(modifier("""{"type":"fillMaxWidth"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.MatchParentSize,
      uiBuilderModifier(modifier("""{"type":"matchParentSize"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Padding(16f, 8f, 16f, 8f),
      uiBuilderModifier(
        modifier("""{"type":"padding","startDp":16,"topDp":8,"endDp":16,"bottomDp":8}""")
      ),
    )
    assertEquals(
      UiBuilderModifierPlan.Size(240f, 96f),
      uiBuilderModifier(modifier("""{"type":"size","widthDp":240,"heightDp":96}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Clip("medium"),
      uiBuilderModifier(modifier("""{"type":"clip","shape":"medium"}""")),
    )
  }

  @Test
  fun `an absent padding edge is zero rather than nothing`() {
    // The wire type defaults each edge and encodes them explicitly, but a document authored by
    // hand need not, and a partial padding is still a padding.
    assertEquals(
      UiBuilderModifierPlan.Padding(0f, 12f, 0f, 0f),
      uiBuilderModifier(modifier("""{"type":"padding","topDp":12}""")),
    )
  }

  @Test
  fun `one dimension is a size and neither is not`() {
    assertEquals(
      UiBuilderModifierPlan.Size(240f, null),
      uiBuilderModifier(modifier("""{"type":"size","widthDp":240}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Size(null, 96f),
      uiBuilderModifier(modifier("""{"type":"size","heightDp":96}""")),
    )
    // Both dimensions are required by the wire type and neither is guaranteed to be a number.
    // This used to throw from inside the composition.
    assertNull(uiBuilderModifier(modifier("""{"type":"size"}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"size","widthDp":null,"heightDp":null}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"size","widthDp":"wide"}""")))
  }

  @Test
  fun `a shape the renderer cannot resolve is refused before it reaches the clip`() {
    // `shape` is a free string on the wire, and resolving it used to error() mid-composition.
    assertEquals(
      UiBuilderModifierPlan.Clip("12"),
      uiBuilderModifier(modifier("""{"type":"clip","shape":"12"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Clip(null),
      uiBuilderModifier(modifier("""{"type":"clip"}""")),
    )
    assertNull(uiBuilderModifier(modifier("""{"type":"clip","shape":"squircle"}""")))
  }

  @Test
  fun `a modifier this build does not know costs one node's layout, not the screen`() {
    // The document is fed by other clients and by later versions of this one. Losing a rounded
    // corner beats losing every part of the screen that does work.
    assertNull(uiBuilderModifier(modifier("""{"type":"rotate","degrees":90}""")))
    assertNull(uiBuilderModifier(modifier("""{"degrees":90}""")))
    assertNull(uiBuilderModifier(modifier("{}")))
  }
}
