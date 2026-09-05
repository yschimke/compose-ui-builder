package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The half of the editor's URL building that is a pure function, and the half that was wrong.
 *
 * [catalogAssetPath] shipped for months emitting `${'$'}{…}` — a literal `$` in an ordinary Kotlin
 * string, not an interpolation — so the browser requested the path
 * `/$%7BencodeUriComponent(catalogSystemId)%7D$path$query` and took a 404. Nothing caught it
 * because its only caller loads the Remote Compose palette and treats any failure as "this box
 * serves no such catalog", so the symptom was an always-empty panel rather than an error.
 *
 * These run on wasmJs rather than in `commonTest` because the function encodes through the
 * browser's own `encodeURIComponent`; a JVM stand-in would be testing a different implementation
 * than the one that ships. [sameOriginRequestUrl] is deliberately not covered here — it reads
 * `window.location`, which is the browser's, not this test's. The performance harness asserts its
 * effect end to end instead, by failing on any 401 the editor provokes against its own server.
 */
class BrowserRequestUrlTest {

  @Test
  fun `a catalog asset path interpolates rather than quoting its own source`() {
    assertEquals("/m3-catalog/api/previews", catalogAssetPath("m3-catalog", "/api/previews"))
    assertEquals("/remote-m3/render/hero.rc", catalogAssetPath("remote-m3", "/render/hero.rc"))
  }

  @Test
  fun `no catalog asset path contains an un-interpolated template`() {
    val path = catalogAssetPath("m3-catalog", "/render/hero.rc")

    // The specific failure, named: a `$` reaching the wire from this function means a template was
    // escaped into a literal again. No legal catalog id or route path produces one.
    assertFalse(path.contains('$'), path)
    assertFalse(path.contains("encodeUriComponent"), path)
  }

  @Test
  fun `a catalog id is encoded, so it cannot open a path of its own`() {
    // `UI_BUILDER_CATALOG_ID` keeps these out on the server side, but this function is also the
    // one that would carry such an id into a request if the rule ever loosened.
    assertEquals("/a%2Fb/api/previews", catalogAssetPath("a/b", "/api/previews"))
    assertEquals("/a%3Fb/api/previews", catalogAssetPath("a?b", "/api/previews"))
  }
}
