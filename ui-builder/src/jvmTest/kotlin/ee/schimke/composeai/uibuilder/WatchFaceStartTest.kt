package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeFrame
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeInput
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeStream
import ee.schimke.composeai.uibuilder.editor.WatchFaceSocket
import ee.schimke.composeai.uibuilder.editor.watchFaceStartOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The start result carries the session's socket, so the pane draws it inline. */
class WatchFaceStartTest {
  private class Stream : UiBuilderNativeStream {
    override val frame: UiBuilderNativeFrame? = null
    override val failure: String? = null

    override fun send(input: UiBuilderNativeInput) = Unit

    override fun close() = Unit
  }

  @Test
  fun `a socket path yields a stream and a controls sender on that socket`() {
    val sent = mutableListOf<String>()
    var opened: String? = null
    val start =
      watchFaceStartOf("/ui-builder/face/watch-face?session=s1", "/api/x/dwf-preview/s1/ws") { path
        ->
        opened = path
        WatchFaceSocket(Stream()) { sent += it }
      }
    assertEquals("/api/x/dwf-preview/s1/ws", opened)
    assertEquals("/ui-builder/face/watch-face?session=s1", start.pagePath)
    assertNotNull(start.stream)
    start.sendControls!!.invoke("{\"ambient\":true}")
    assertEquals(listOf("{\"type\":\"controls\",\"controls\":{\"ambient\":true}}"), sent)
  }

  @Test
  fun `no socket path, or a socket that will not open, leaves only the tab`() {
    for (path in listOf(null, "")) {
      val start = watchFaceStartOf("/tab", path) { error("must not open") }
      assertEquals("/tab", start.pagePath)
      assertNull(start.stream)
      assertNull(start.sendControls)
    }
    val failed = watchFaceStartOf("/tab", "/ws") { error("blocked") }
    assertEquals("/tab", failed.pagePath)
    assertNull(failed.stream)
  }
}
