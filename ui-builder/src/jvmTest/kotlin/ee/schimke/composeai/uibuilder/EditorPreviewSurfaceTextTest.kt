package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EditorPreviewSurfaceTextTest {
  @Test
  fun `every additive layout warns when its wasm editor is a stand-in`() {
    val surfaces =
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

    EditorPreviewSurface.entries.forEach { surface ->
      assertContains(surface.supportingText(surfaces), "Wasm stand-in, for authoring")
    }
  }

  @Test
  fun `authoritative wasm descriptions stay concise`() {
    assertEquals("Visual editor · Wasm", EditorPreviewSurface.Wasm.supportingText())
    assertEquals(
      "Editor · Wasm + static target preview",
      EditorPreviewSurface.Native.supportingText(),
    )
    assertEquals(
      "Editor · Wasm + static target + interactive preview",
      EditorPreviewSurface.Both.supportingText(),
    )
  }
}
