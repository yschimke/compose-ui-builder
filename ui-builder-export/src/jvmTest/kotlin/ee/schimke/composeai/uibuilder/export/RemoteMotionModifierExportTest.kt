package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A shared element's timing and ways in and out, and `animateEnterExit`, written as the creation
 * API's own calls.
 */
class RemoteMotionModifierExportTest {
  private fun export(modifiers: String): WearWidgetCodeExporter.Result =
    WearWidgetCodeExporter.export(
      Json.decodeFromJsonElement<UiBuilderDocument>(
        Json.parseToJsonElement(
          """
          {"schema":"ui-builder-design-v1","id":"motion","title":"Motion","revision":1,
           "catalogPin":{},"environment":{},"stateVariables":{},
           "roots":["host"],
           "nodes":{
            "host":{"id":"host","componentId":"remote-m3/widget-container-small",
              "slots":{"content":["box"]}},
            "box":{"id":"box","componentId":"layout/box","modifiers":$modifiers}}}
          """
        ) as JsonObject
      )
    )

  private fun source(modifiers: String): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(export(modifiers)).source

  @Test
  fun `a shared element with only a key keeps the overload every line has`() {
    val source = source("""[{"type":"sharedElement","key":2}]""")

    assertContains(source, "animationSpec(2, true)")
    assertFalse("RemoteEnterTransition" in source, source)
  }

  @Test
  fun `a shared element with a timing and ways in and out is sharedElement's own call`() {
    val source =
      source(
        """[{"type":"sharedElement","key":1,"durationMs":450,"easing":"overshoot",
             "enter":"slideInBottom","exit":"fadeOut"}]"""
      )

    assertContains(
      source,
      "sharedElement(1, remoteTween(450, RemoteEasing.Overshoot), " +
        "RemoteEnterTransition.SlideInBottom, RemoteExitTransition.FadeOut)",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.modifier.sharedElement")
    assertContains(
      source,
      "import androidx.compose.remote.creation.compose.modifier.RemoteEnterTransition",
    )
    assertContains(source, "import androidx.compose.remote.creation.compose.state.remoteTween")
  }

  /**
   * The creation library's `animateEnterExit` writes animation id 0, which every player reads as
   * "disabled"; the export writes the unset id, -1, so the transitions play.
   */
  @Test
  fun `animate in and out is an enabled spec matched to nothing`() {
    val source = source("""[{"type":"animateEnterExit","enter":"slideInLeft","durationMs":200}]""")

    assertContains(
      source,
      "animationSpec(-1, remoteTween(200, RemoteEasing.Standard), " +
        "remoteTween(200, RemoteEasing.Standard), RemoteEnterTransition.SlideInLeft, " +
        "RemoteExitTransition.FadeOut)",
    )
    assertFalse("animateEnterExit(" in source, source)
  }

  @Test
  fun `a transition the creation API has not got is refused where it is`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        export("""[{"type":"animateEnterExit","enter":"spin"}]""")
      )

    assertTrue(refused.reasons.any { "box" in it && "`spin`" in it }, refused.reasons.toString())
  }
}
