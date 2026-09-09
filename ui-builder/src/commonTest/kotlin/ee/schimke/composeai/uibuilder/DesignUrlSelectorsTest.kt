package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The URL grammar, tested apart from the browser.
 *
 * Every input here is somebody else's link — pasted out of a chat, hand-edited, produced by a tool
 * that knows one key and not the others — so what is asserted is mostly what the parser *survives*
 * rather than what it recognises. The round trip is the other half: a link this code builds has to
 * be a link this code reads back, or "Copy link" is a button that produces addresses nobody can
 * follow.
 *
 * In `commonTest` rather than `wasmJsTest` because none of it goes through a browser intrinsic —
 * the percent-encoding is written in `commonMain` precisely so the JVM the test runs on and the
 * Wasm the editor ships as agree on every byte.
 */
class DesignUrlSelectorsTest {
  @Test
  fun `a bare design URL selects nothing`() {
    assertTrue(parseDesignUrlSelectors(null, null).isEmpty)
    assertTrue(parseDesignUrlSelectors("", "").isEmpty)
    assertTrue(parseDesignUrlSelectors("?", "#").isEmpty)
  }

  @Test
  fun `the three selectors are read from the query and the fragment`() {
    assertEquals(
      DesignUrlSelectors(revision = 41, nodeId = "primary-button", threadId = "t-7"),
      parseDesignUrlSelectors("?revision=41&node=primary-button", "#thread=t-7"),
    )
  }

  @Test
  fun `the browser's leading punctuation is optional`() {
    assertEquals(
      DesignUrlSelectors(revision = 41, nodeId = "primary-button", threadId = "t-7"),
      parseDesignUrlSelectors("revision=41&node=primary-button", "thread=t-7"),
    )
  }

  @Test
  fun `identity and transport keys are not selectors`() {
    val selectors =
      parseDesignUrlSelectors(
        "?actor=github%3Ayschimke&clientId=browser-a&token=secret&node=hero&unknown=1",
        null,
      )
    assertEquals(DesignUrlSelectors(nodeId = "hero"), selectors)
  }

  @Test
  fun `a revision that is not a whole non-negative number is no revision at all`() {
    listOf("?revision=", "?revision=latest", "?revision=-3", "?revision=4.5").forEach {
      assertNull(parseDesignUrlSelectors(it, null).revision, "for $it")
    }
  }

  @Test
  fun `revision zero is the revision a design was created at`() {
    assertEquals(0L, parseDesignUrlSelectors("?revision=0", null).revision)
    assertEquals(
      "/ui-builder/m3-catalog/jetcaster-discover?revision=0",
      designUrlPath("m3-catalog", "jetcaster-discover", DesignUrlSelectors(revision = 0)),
    )
  }

  @Test
  fun `a thread is read from the fragment and never from the query`() {
    assertNull(parseDesignUrlSelectors("?thread=t-7", null).threadId)
    assertEquals("t-7", parseDesignUrlSelectors(null, "#thread=t-7").threadId)
  }

  @Test
  fun `a revision or node written into the fragment is ignored`() {
    val selectors = parseDesignUrlSelectors(null, "#revision=41&node=hero&thread=t-7")
    assertEquals(DesignUrlSelectors(threadId = "t-7"), selectors)
  }

  @Test
  fun `a fragment carrying more than the thread still yields the thread`() {
    assertEquals("t-7", parseDesignUrlSelectors(null, "#anchor&thread=t-7&other=x").threadId)
  }

  @Test
  fun `percent-encoded values are decoded`() {
    val selectors = parseDesignUrlSelectors("?node=hero%20card", "#thread=t%2F7")
    assertEquals("hero card", selectors.nodeId)
    assertEquals("t/7", selectors.threadId)
  }

  @Test
  fun `a malformed escape opens the design rather than failing`() {
    assertEquals("100%", parseDesignUrlSelectors("?node=100%", null).nodeId)
  }

  @Test
  fun `a blank id is treated as absent`() {
    assertNull(parseDesignUrlSelectors("?node=", null).nodeId)
    assertNull(parseDesignUrlSelectors("?node=%20%20", null).nodeId)
  }

  @Test
  fun `a long id is read, because the service stores one`() {
    // Blankness is the only thing the service refuses a node id for, so a cap here would make the
    // link builder emit an address this parser could not read back.
    val long = "n".repeat(4096)
    assertEquals(long, parseDesignUrlSelectors("?node=$long", null).nodeId)
    assertEquals(long, parseDesignUrlSelectors(null, "#thread=$long").threadId)
  }

  @Test
  fun `an id keeps the whitespace it was written with`() {
    // The service rejects a node id only when it is blank, so " hero " is an id a design can have.
    // A parser that trimmed it would disagree with the link builder, which encodes the id exactly.
    val selectors = parseDesignUrlSelectors("?node=%20hero%20", null)
    assertEquals(" hero ", selectors.nodeId)
    assertEquals(
      "/ui-builder/m3-catalog/d?node=%20hero%20",
      designUrlPath("m3-catalog", "d", DesignUrlSelectors(nodeId = " hero ")),
    )
  }

  @Test
  fun `the last value of a repeated key wins`() {
    assertEquals("second", parseDesignUrlSelectors("?node=first&node=second", null).nodeId)
  }

  @Test
  fun `a link names the catalog and the design and nothing else`() {
    assertEquals(
      "/ui-builder/m3-catalog/jetcaster-discover",
      designUrlPath("m3-catalog", "jetcaster-discover"),
    )
  }

  @Test
  fun `a node link pins the revision it was copied at`() {
    assertEquals(
      "/ui-builder/m3-catalog/jetcaster-discover?revision=41&node=primary-button",
      designUrlPath(
        "m3-catalog",
        "jetcaster-discover",
        DesignUrlSelectors(revision = 41, nodeId = "primary-button"),
      ),
    )
  }

  @Test
  fun `a thread link carries the thread in the fragment`() {
    assertEquals(
      "/ui-builder/m3-catalog/jetcaster-discover?node=primary-button#thread=t-7",
      designUrlPath(
        "m3-catalog",
        "jetcaster-discover",
        DesignUrlSelectors(nodeId = "primary-button", threadId = "t-7"),
      ),
    )
  }

  @Test
  fun `every selector survives a round trip`() {
    val original = DesignUrlSelectors(revision = 41, nodeId = "hero card", threadId = "t/7")
    val url = designUrlPath("m3-catalog", "jetcaster-discover", original)
    val query = url.substringAfter('?', "").substringBefore('#')
    val fragment = url.substringAfter('#', "")
    assertEquals(original, parseDesignUrlSelectors(query, fragment))
  }

  @Test
  fun `a design the path form cannot name is refused rather than mis-linked`() {
    // The service stores any id that is not blank, but the editor will not start on a design named
    // in the path unless the id is path-safe. A link builder that encoded such an id would produce
    // an address its recipient could not open.
    assertTrue(isDesignUrlPathSafe("m3-catalog", "jetcaster-discover"))
    assertFalse(isDesignUrlPathSafe("m3-catalog", "hero design"))
    assertFalse(isDesignUrlPathSafe("m3-catalog", "-leading-dash"))
    assertFalse(isDesignUrlPathSafe("m3 catalog", "jetcaster-discover"))
    // A design may legitimately be called this, and the app shell would route it as a file.
    assertFalse(isDesignUrlPathSafe("m3-catalog", "screen.png"))
    assertFalse(isDesignUrlPathSafe("m3-catalog", "prototype.mjs"))
    assertTrue(isDesignUrlPathSafe("m3-catalog", "screen.v2"))
    assertFailsWith<IllegalArgumentException> { designUrlPath("m3-catalog", "hero design") }
  }

  @Test
  fun `no identity key is ever written into a link`() {
    val url =
      designUrlPath(
        "m3-catalog",
        "jetcaster-discover",
        DesignUrlSelectors(revision = 41, nodeId = "hero", threadId = "t-7"),
      )
    DESIGN_URL_IDENTITY_KEYS.forEach { key -> assertTrue(key !in url, "$key leaked into $url") }
  }

  @Test
  fun `encoding matches encodeURIComponent`() {
    assertEquals("a%20b", encodeUrlComponent("a b"))
    assertEquals("a%2Fb", encodeUrlComponent("a/b"))
    assertEquals("-_.!~*'()", encodeUrlComponent("-_.!~*'()"))
    assertEquals("%C3%A9", encodeUrlComponent("é"))
    assertEquals("é", decodeUrlComponent("%C3%A9"))
    assertEquals("a b", decodeUrlComponent("a+b"))
  }
}
