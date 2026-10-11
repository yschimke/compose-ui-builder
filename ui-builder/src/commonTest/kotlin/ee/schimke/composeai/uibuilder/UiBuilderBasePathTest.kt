package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals

class UiBuilderBasePathTest {

  @Test
  fun `a page with no meta, or a malformed one, keeps the ui-builder base`() {
    for (raw in
      listOf(
        null,
        "",
        "  ",
        "ui-builder",
        "//evil.example/",
        "https://evil.example/",
        // A browser reads `\` as `/` and drops tabs and newlines in an http(s) URL, so each of
        // these is `//evil.example/` once a navigation is built from it.
        "/\\evil.example/",
        "/\tevil/\t/evil.example/",
        "/\n/evil.example/",
        "/ui-builder?x=1",
        "/ui-builder#x",
      )) {
      assertEquals(DEFAULT_UI_BUILDER_BASE_PATH, normalizeUiBuilderBasePath(raw), "for $raw")
    }
  }

  @Test
  fun `a base is normalised to one leading and one trailing slash`() {
    assertEquals("/", normalizeUiBuilderBasePath("/"))
    assertEquals("/", normalizeUiBuilderBasePath("///"))
    assertEquals("/ui-builder/", normalizeUiBuilderBasePath("/ui-builder"))
    assertEquals("/a/b/", normalizeUiBuilderBasePath(" /a//b "))
  }

  @Test
  fun `under the default base a design is the one segment after ui-builder`() {
    val base = DEFAULT_UI_BUILDER_BASE_PATH
    assertEquals("d1", designIdFromUiBuilderPath("/ui-builder/d1", base))
    assertEquals("d1", designIdFromUiBuilderPath("/ui-builder/d1/", base))
    assertEquals("a b", designIdFromUiBuilderPath("/ui-builder/a%20b", base))
    for (path in
      listOf(
        "/ui-builder/",
        "/ui-builder",
        "/ui-builder/designs",
        "/ui-builder/d1/access",
        "/d1",
        "/",
      )) {
      assertEquals("", designIdFromUiBuilderPath(path, base), "for $path")
    }
  }

  @Test
  fun `at the root a design is the first and only segment`() {
    assertEquals("d1", designIdFromUiBuilderPath("/d1", "/"))
    assertEquals("a b", designIdFromUiBuilderPath("/a%20b", "/"))
    for (path in listOf("/", "/designs", "/d1/access", "/d1/history")) {
      assertEquals("", designIdFromUiBuilderPath(path, "/"), "for $path")
    }
  }

  @Test
  fun `the default base parses exactly as the editor did before the base path existed`() {
    // The parser this replaced, as it was in Main.kt's @JsFun, so a page with no meta is provably
    // unchanged: parts[0] == 'ui-builder', exactly two parts, and not the designs index.
    fun before(pathname: String): String {
      val parts = pathname.split('/').filter(String::isNotEmpty)
      if (parts.getOrNull(0) != "ui-builder" || parts.size != 2 || parts[1] == "designs") return ""
      return decodeUrlComponent(parts[1])
    }
    for (path in
      listOf(
        "/",
        "/ui-builder",
        "/ui-builder/",
        "/ui-builder/designs",
        "/ui-builder/designs/",
        "/ui-builder/jetcaster-discover",
        "/ui-builder/jetcaster-discover/",
        "/ui-builder/a%2Fb",
        "/ui-builder/d1/access",
        "/ui-builder/m3-catalog/d1",
        "/elsewhere/d1",
        "/d1",
      )) {
      assertEquals(
        before(path),
        designIdFromUiBuilderPath(path, DEFAULT_UI_BUILDER_BASE_PATH),
        "for $path",
      )
    }
  }

  @Test
  fun `pages are built under the base, each segment encoded`() {
    assertEquals("/ui-builder/d1", uiBuilderPagePath(DEFAULT_UI_BUILDER_BASE_PATH, "d1"))
    assertEquals(
      "/ui-builder/d1/access",
      uiBuilderPagePath(DEFAULT_UI_BUILDER_BASE_PATH, "d1", "access"),
    )
    assertEquals(
      "/ui-builder/designs",
      uiBuilderPagePath(DEFAULT_UI_BUILDER_BASE_PATH, UI_BUILDER_DESIGNS_SEGMENT),
    )
    assertEquals("/d1", uiBuilderPagePath("/", "d1"))
    assertEquals("/d1/access", uiBuilderPagePath("/", "d1", "access"))
    assertEquals("/designs", uiBuilderPagePath("/", UI_BUILDER_DESIGNS_SEGMENT))
    assertEquals("/m3-catalog/a%20b", uiBuilderPagePath("/", "m3-catalog", "a b"))
  }

  @Test
  fun `a shared design link follows the base, selectors and all`() {
    val selectors = DesignUrlSelectors(revision = 3, nodeId = "hero", threadId = "t1")
    assertEquals(
      "/ui-builder/d1?revision=3&node=hero#thread=t1",
      designUrlPath("d1", selectors),
    )
    assertEquals("/d1?revision=3&node=hero#thread=t1", designUrlPath("d1", selectors, "/"))
    assertEquals("/d1", designUrlPath("d1", basePath = "/"))
  }
}
