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
    assertNull(uiBuilderModifier(modifier("""{"type":"shimmer","period":90}""")))
    assertNull(uiBuilderModifier(modifier("""{"degrees":90}""")))
    assertNull(uiBuilderModifier(modifier("{}")))
  }

  @Test
  fun `the vocabulary contracts 2_8_0 added is read too`() {
    assertEquals(
      UiBuilderModifierPlan.FillMaxHeight,
      uiBuilderModifier(modifier("""{"type":"fillMaxHeight"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Width(160f),
      uiBuilderModifier(modifier("""{"type":"width","widthDp":160}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Height(48f),
      uiBuilderModifier(modifier("""{"type":"height","heightDp":48}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.WidthIn(240f, 480f),
      uiBuilderModifier(modifier("""{"type":"widthIn","minDp":240,"maxDp":480}""")),
    )
    // One edge is a bound; the wire allows either to be absent.
    assertEquals(
      UiBuilderModifierPlan.HeightIn(56f, null),
      uiBuilderModifier(modifier("""{"type":"heightIn","minDp":56}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.AspectRatio(1.5f),
      uiBuilderModifier(modifier("""{"type":"aspectRatio","ratio":1.5}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.WrapContentSize("center"),
      uiBuilderModifier(modifier("""{"type":"wrapContentSize","alignment":"center"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Offset(4f, -2f),
      uiBuilderModifier(modifier("""{"type":"offset","xDp":4,"yDp":-2}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.ZIndex(2f),
      uiBuilderModifier(modifier("""{"type":"zIndex","zIndex":2}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Background("surfaceContainerHigh", "medium"),
      uiBuilderModifier(
        modifier(
          """{"type":"background","color":{"type":"colorToken","value":"surfaceContainerHigh"},"shape":"medium"}"""
        )
      ),
    )
    assertEquals(
      UiBuilderModifierPlan.Border(1f, "#FF335577", null),
      uiBuilderModifier(
        modifier("""{"type":"border","widthDp":1,"color":{"type":"color","value":"#FF335577"}}""")
      ),
    )
    assertEquals(
      UiBuilderModifierPlan.Alpha(0.8f),
      uiBuilderModifier(modifier("""{"type":"alpha","alpha":0.8}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Shadow(6f, "large", true),
      uiBuilderModifier(
        modifier("""{"type":"shadow","elevationDp":6,"shape":"large","clip":true}""")
      ),
    )
    assertEquals(
      UiBuilderModifierPlan.Rotate(90f),
      uiBuilderModifier(modifier("""{"type":"rotate","degrees":90}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Scale(1.25f, 0.75f),
      uiBuilderModifier(modifier("""{"type":"scale","scaleX":1.25,"scaleY":0.75}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.VerticalScroll,
      uiBuilderModifier(modifier("""{"type":"verticalScroll"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.HorizontalScroll,
      uiBuilderModifier(modifier("""{"type":"horizontalScroll"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.TestTag("hero-card"),
      uiBuilderModifier(modifier("""{"type":"testTag","tag":"hero-card"}""")),
    )
  }

  @Test
  fun `the scoped modifiers name an alignment their axis defines, or nothing`() {
    assertEquals(
      UiBuilderModifierPlan.Align("bottomEnd"),
      uiBuilderModifier(modifier("""{"type":"align","alignment":"bottomEnd"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.AlignHorizontal("end"),
      uiBuilderModifier(modifier("""{"type":"alignHorizontal","alignment":"end"}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.AlignVertical("centerVertically"),
      uiBuilderModifier(modifier("""{"type":"alignVertical","alignment":"centerVertically"}""")),
    )
    // A box's nine and an axis's three are different vocabularies, and the wire is one string for
    // all three modifiers. `Alignment.CenterEnd` is not an `Alignment.Horizontal`.
    assertNull(
      uiBuilderModifier(modifier("""{"type":"alignHorizontal","alignment":"centerEnd"}"""))
    )
    assertNull(uiBuilderModifier(modifier("""{"type":"alignVertical","alignment":"start"}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"align","alignment":"sideways"}""")))
  }

  @Test
  fun `a weight is positive, and fill is only what the document says`() {
    assertEquals(
      UiBuilderModifierPlan.Weight(2f, null),
      uiBuilderModifier(modifier("""{"type":"weight","weight":2}""")),
    )
    assertEquals(
      UiBuilderModifierPlan.Weight(1f, false),
      uiBuilderModifier(modifier("""{"type":"weight","weight":1,"fill":false}""")),
    )
    // `Modifier.weight` requires a positive weight and throws on anything else, from inside the
    // layout pass where nothing can say which node caused it.
    assertNull(uiBuilderModifier(modifier("""{"type":"weight","weight":0}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"weight","weight":-1}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"weight"}""")))
  }

  @Test
  fun `a value the renderer would throw on is refused before it is applied`() {
    // Same rule as `size` naming neither dimension: the layout pass is not the place to find out.
    assertNull(uiBuilderModifier(modifier("""{"type":"aspectRatio","ratio":0}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"widthIn"}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"scale","scaleX":2}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"testTag","tag":""}""")))
    assertNull(uiBuilderModifier(modifier("""{"type":"wrapContentSize","alignment":"sideways"}""")))
    // A colour the theme cannot resolve, which used to be an `error()` inside the composition.
    assertNull(
      uiBuilderModifier(
        modifier("""{"type":"background","color":{"type":"colorToken","value":"chartreuse"}}""")
      )
    )
    assertNull(
      uiBuilderModifier(
        modifier("""{"type":"border","color":{"type":"color","value":"#FF000000"}}""")
      )
    )
  }
}
