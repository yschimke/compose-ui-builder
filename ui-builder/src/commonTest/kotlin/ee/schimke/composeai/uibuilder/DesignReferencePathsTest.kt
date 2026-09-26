package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignReferencePathsTest {
  @Test
  fun `a Remote Compose reference reaches the render lane and ingested documents`() {
    listOf("/render/hero.rc", "/remote-m3/render/hero.rc", "/d/abc123/raw").forEach {
      assertTrue(DesignReferenceKind.RemoteComposeDocument.allows(it), it)
    }
  }

  @Test
  fun `a Lottie reference reaches ingested documents only`() {
    assertTrue(DesignReferenceKind.LottieAnimation.allows("/d/abc123/raw"))
    assertFalse(DesignReferenceKind.LottieAnimation.allows("/render/hero.rc"))
  }

  @Test
  fun `every other route on the origin is refused`() {
    listOf(
        "/",
        "/api/ui-builder/v1/identity",
        "/api/ui-builder/v1/designs/settings/document",
        "/admin/ui-builder/designs/settings/document",
        "/mcp",
        "/render/hero.png",
        "/a/b/render/hero.rc",
        "/render/../api/x.rc",
        "/render/%2e%2e%2fapi.rc",
        "/remote-m3%2Fapi/render/hero.rc",
        "/d/abc/raw/extra",
        "/d//raw",
      )
      .forEach { path ->
        DesignReferenceKind.entries.forEach { kind ->
          assertFalse(kind.allows(path), "$kind $path")
        }
      }
  }
}
