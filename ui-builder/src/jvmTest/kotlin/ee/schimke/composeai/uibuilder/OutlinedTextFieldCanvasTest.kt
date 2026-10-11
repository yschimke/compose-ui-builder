package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test

/**
 * The published m3-catalog serves the outlined field as its own component,
 * `m3/outlined-text-field`, where the packaged catalog spells it `m3/text-field` with `variant:
 * outlined`. The canvas draws both as a real `OutlinedTextField`: a design built against the served
 * catalog drew "Unsupported component" in `ui_builder_view`'s PNG.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinedTextFieldCanvasTest {
  @Test
  fun `the published outlined text field draws as a text field`() =
    runDesktopComposeUiTest(width = 400, height = 200) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(DOCUMENT)) }
      // Fails before the fix: "Unsupported component: m3/outlined-text-field".
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
      onNode(hasSetTextAction()).assertExists()
      onNodeWithText("Email").assertExists()
    }

  /** The `TextFieldState` field keeps what is typed into it, as its generated call does. */
  @Test
  fun `typing into the published outlined text field keeps the text`() =
    runDesktopComposeUiTest(width = 400, height = 200) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(DOCUMENT)) }
      onNode(hasSetTextAction()).performTextInput("ada")
      onNodeWithText("ada", substring = true).assertExists()
      // The published record's prefix and suffix slots, which Material shows once there is text.
      onNodeWithText("mailto:").assertExists()
      onNodeWithText("@example.com").assertExists()
    }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"outlined-field",
       "title":"Outlined field","revision":1,
       "catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":400,"heightDp":200,"density":1.0,"theme":"light",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["email"],
       "nodes":{
        "email":{"id":"email","componentId":"m3/outlined-text-field",
          "properties":{"singleLine":{"type":"bool","value":true}},
          "modifiers":[],"slots":{"label":["email-label"],"prefix":["email-prefix"],
            "suffix":["email-suffix"]},"eventBindings":{}},
        "email-prefix":{"id":"email-prefix","componentId":"m3/text",
          "properties":{"text":{"type":"string","value":"mailto:"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "email-suffix":{"id":"email-suffix","componentId":"m3/text",
          "properties":{"text":{"type":"string","value":"@example.com"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "email-label":{"id":"email-label","componentId":"m3/text",
          "properties":{"text":{"type":"string","value":"Email"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
