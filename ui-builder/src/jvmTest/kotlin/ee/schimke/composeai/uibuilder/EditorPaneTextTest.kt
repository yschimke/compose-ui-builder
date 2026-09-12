package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorPaneTextTest {
  private val standInSurfaces =
    UiBuilderPreviewSurfaces(
      wasm =
        UiBuilderPreviewSurfaces.SurfaceClaim(
          UiBuilderPreviewSurfaces.Fidelity.APPROXIMATE,
          reason = "The real library is Android-only.",
        ),
      native =
        UiBuilderPreviewSurfaces.SurfaceClaim(
          UiBuilderPreviewSurfaces.Fidelity.AUTHORITATIVE,
          backend = UiBuilderPreviewSurfaces.BACKEND_ANDROID,
        ),
    )

  @Test
  fun `both browser panes warn when the wasm renderer is a stand-in`() {
    // Only the two panes this browser draws: the native one is the catalog's authoritative lane
    // and saying "stand-in" under it would be the one sentence in the menu that is false.
    listOf(EditorPane.Editor, EditorPane.Preview).forEach { pane ->
      assertContains(pane.supportingText(standInSurfaces), "Wasm stand-in, for authoring")
    }
    assertContains(
      EditorPane.Native.supportingText(standInSurfaces),
      "Compiled on the host · Android",
    )
  }

  @Test
  fun `authoritative wasm descriptions stay concise`() {
    assertEquals("Edit the design · Wasm", EditorPane.Editor.supportingText())
    assertEquals(
      "Devices and configurations, not editable · Wasm",
      EditorPane.Preview.supportingText(),
    )
    assertEquals("Compiled on the host · the target platform", EditorPane.Native.supportingText())
  }

  @Test
  fun `the preview pane never claims to compile`() {
    // The whole reason it is a separate pane from the native one: switching it on costs nothing.
    assertTrue(EditorPane.Preview.supportingText().contains("not editable"))
    assertTrue(!EditorPane.Preview.supportingText().contains("Compiled"))
  }

  @Test
  fun `a refused native pane carries the catalog's own sentence`() {
    val refused =
      UiBuilderPreviewSurfaces(
        native =
          UiBuilderPreviewSurfaces.SurfaceClaim(
            UiBuilderPreviewSurfaces.Fidelity.NONE,
            reason = "No compile lane is configured for this project.",
          )
      )
    assertEquals(
      "Unavailable: No compile lane is configured for this project.",
      EditorPane.Native.unavailableText(refused),
    )
    // The browser panes are never unavailable, so they say what they always say.
    assertEquals(
      EditorPane.Editor.supportingText(),
      EditorPane.Editor.unavailableText(),
    )
  }

  @Test
  fun `the toolbar names the open panes rather than counting them`() {
    assertEquals("Editor", panesLabel(setOf(EditorPane.Editor)))
    assertEquals("Editor + Preview", panesLabel(setOf(EditorPane.Preview, EditorPane.Editor)))
    assertEquals(
      "Editor + Preview + Native",
      panesLabel(setOf(EditorPane.Native, EditorPane.Editor, EditorPane.Preview)),
    )
    // Declaration order, whatever order they were switched on in.
    assertEquals("Preview + Native", panesLabel(setOf(EditorPane.Native, EditorPane.Preview)))
  }
}
