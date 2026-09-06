package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The three-scope tree from the design docs, asserted node by node.
 *
 * ```
 * Column → Text → Remote Compose → RemoteColumn → Text
 *                                              → Custom("field") → Text
 *                                              → Text
 *                              → Text
 * ```
 *
 * Every one of those `m3/text` nodes is the same component, and which vocabulary it is written in
 * is decided entirely by where it sits — which is the claim [RemoteScopes] exists to make good on.
 */
class RemoteScopesTest {

  @Test
  fun `scope switches on at inline content and back off inside a custom component`() {
    val scopes = RemoteScopes.of(threeScopeDocument())

    assertFalse(scopes.isRemote("screen"), "the enclosing column is Compose")
    assertFalse(scopes.isRemote("intro"), "a sibling above the remote content is Compose")
    assertFalse(scopes.isRemote("outro"), "and so is one below it")
    // The host node itself is not remote: it is the boundary, and a mobile screen is what holds it.
    assertFalse(scopes.isRemote("remote"), "the inline node is the switch, not the switched")
    assertTrue(scopes.isRemote("remoteColumn"), "its body is")
    assertTrue(scopes.isRemote("remoteLabel"), "and so is everything under it")
    assertTrue(scopes.isRemote("custom"), "the custom component is an operation in the document")
    assertFalse(scopes.isRemote("field"), "what fills it is host content again")
  }

  @Test
  fun `a widget body is remote without anything saying so`() {
    val widget =
      document(
        roots = listOf("widget"),
        nodes =
          listOf(
            node("widget", WearWidgetScaffoldSize.Small.componentId, "content" to listOf("body")),
            node("body", "layout/column", "children" to listOf("label")),
            node("label", "m3/text"),
          ),
      )
    val scopes = RemoteScopes.of(widget)

    assertFalse(scopes.isRemote("widget"), "the container is the host frame, which is erased")
    assertTrue(scopes.isRemote("body"))
    assertTrue(scopes.isRemote("label"))
  }

  @Test
  fun `a design with no remote content has no remote scope`() {
    val plain =
      document(
        roots = listOf("screen"),
        nodes =
          listOf(
            node("screen", "layout/column", "children" to listOf("intro")),
            node("intro", "m3/text"),
          ),
      )
    assertTrue(RemoteScopes.of(plain).isEmpty)
  }

  @Test
  fun `a cycle is walked once rather than for ever`() {
    val looped =
      document(
        roots = listOf("a"),
        nodes =
          listOf(
            node("a", "layout/column", "children" to listOf("b")),
            node("b", "layout/column", "children" to listOf("a")),
          ),
      )
    // The export gate reports `GRAPH_CYCLE`; this only has to return, which is the whole assertion.
    assertTrue(RemoteScopes.of(looped).isEmpty)
  }

  @Test
  fun `the inline body generates a RemoteComposable function in the remote vocabulary`() {
    val result = InlineRemoteContentExporter.export(threeScopeDocument(), "remote")
    val refused = result as? InlineRemoteContentExporter.Result.Refused

    // The custom component is the one node the vocabulary cannot write, and it is refused by name
    // rather than approximated. Everything else in this tree it can.
    assertTrue(refused != null, "a custom component is refused: $result")
    assertTrue(
      refused.reasons.any { "custom component `custom`" in it && "field" in it },
      refused.reasons.toString(),
    )
  }

  @Test
  fun `an inline body without a custom component emits RemoteColumn and RemoteText`() {
    val design =
      document(
        roots = listOf("screen"),
        nodes =
          listOf(
            node("screen", "layout/column", "children" to listOf("remote")),
            node("remote", REMOTE_COMPOSE_INLINE_COMPONENT_ID, "content" to listOf("remoteColumn")),
            node("remoteColumn", "layout/column", "children" to listOf("remoteLabel")),
            text("remoteLabel", "Now playing"),
          ),
        title = "Player screen",
      )

    val emitted =
      InlineRemoteContentExporter.export(design, "remote", packageName = "generated.uibuilder")
        as InlineRemoteContentExporter.Result.Emitted

    assertEquals("PlayerScreenRemoteRemoteContent", emitted.functionName)
    assertTrue("package generated.uibuilder" in emitted.source, emitted.source)
    assertTrue("@RemoteComposable" in emitted.source, emitted.source)
    assertTrue("RemoteColumn {" in emitted.source, emitted.source)
    assertTrue("""RemoteText(text = "Now playing".rs)""" in emitted.source, emitted.source)
    // The Glance widget half of the import set belongs to a widget, not to content inside a screen.
    assertFalse("androidx.glance.wear" in emitted.source, emitted.source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.layout.RemoteColumn" in emitted.source,
      emitted.source,
    )
  }

  @Test
  fun `only an inline node has a body to generate`() {
    val refused =
      InlineRemoteContentExporter.export(threeScopeDocument(), "screen")
        as InlineRemoteContentExporter.Result.Refused
    assertTrue(refused.reasons.single().contains("layout/column"), refused.reasons.toString())
  }

  private fun threeScopeDocument(): UiBuilderDocument =
    document(
      roots = listOf("screen"),
      nodes =
        listOf(
          node("screen", "layout/column", "children" to listOf("intro", "remote", "outro")),
          text("intro", "Library"),
          node("remote", REMOTE_COMPOSE_INLINE_COMPONENT_ID, "content" to listOf("remoteColumn")),
          node("remoteColumn", "layout/column", "children" to listOf("remoteLabel", "custom")),
          text("remoteLabel", "Search"),
          UiBuilderNode(
            id = "custom",
            componentId = REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
            properties =
              buildJsonObject {
                putJsonObject("name") {
                  put("type", "string")
                  put("value", "field")
                }
              },
            slots = mapOf("content" to listOf("field")),
          ),
          text("field", "type here"),
          text("outro", "Recently played"),
        ),
    )

  private fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        buildJsonObject {
          putJsonObject("text") {
            put("type", "string")
            put("value", value)
          }
        },
    )

  private fun node(id: String, componentId: String, vararg slots: Pair<String, List<String>>) =
    UiBuilderNode(id = id, componentId = componentId, slots = slots.toMap())

  private fun document(
    roots: List<String>,
    nodes: List<UiBuilderNode>,
    title: String = "Design",
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "design",
      title = title,
      revision = 1,
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("m3-catalog"))),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = roots,
      nodes = nodes.associateBy { it.id },
    )
}
