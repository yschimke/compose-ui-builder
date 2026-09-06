package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A mobile screen holding remote content, judged by the two things that judge a design: the catalog
 * and the Compose export.
 *
 * The tree is the one from `docs/design/UI_BUILDER_REMOTE_COMPOSE.md` — a Column with Text either
 * side of a Remote Compose node whose body holds a custom component filled with ordinary Compose.
 */
class InlineRemoteContentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))

  @Test
  fun `the mobile catalog offers both halves of the seam`() {
    val inline = catalog.componentsById.getValue(REMOTE_COMPOSE_INLINE_COMPONENT_ID)
    val custom = catalog.componentsById.getValue(REMOTE_COMPOSE_CUSTOM_COMPONENT_ID)

    // One body, not a list: a Remote Compose document has one root, and the emitter refuses more.
    assertEquals(1, inline.slotsByName.getValue("content").cardinality.max)
    // Narrowed to the vocabulary the emitter can write, so the palette cannot offer a `m3/button`
    // for a slot whose contents become `@RemoteComposable`.
    assertEquals(
      listOf("RemoteAuthorable"),
      inline.slotsByName.getValue("content").acceptedTraits,
    )
    assertTrue("RemoteAuthorable" in catalog.componentsById.getValue("m3/text").traits)
    assertTrue("RemoteAuthorable" in catalog.componentsById.getValue("layout/column").traits)
    assertTrue("RemoteAuthorable" in custom.traits, "a custom component goes inside remote content")
    // And its own slot takes anything, because what fills a custom component is host content.
    assertEquals(listOf("AnyContent"), custom.slotsByName.getValue("content").acceptedTraits)
    assertTrue(custom.propertiesByName.getValue("name").required)
  }

  @Test
  fun `an embedded document may name its source as bytes or as a URL`() {
    val document = catalog.componentsById.getValue("remote-compose/document")

    // Neither is required on its own — a node carries one or the other — and the editor's own rule
    // is what reports a node carrying neither. Declaring both required would refuse every design.
    assertTrue(!document.propertiesByName.getValue("documentBase64").required)
    assertTrue(!document.propertiesByName.getValue("documentUrl").required)
  }

  @Test
  fun `the Compose export refuses remote content by name and says nothing about its subtree`() {
    val diagnostics =
      CapabilityComposeCodeExporter.diagnose(screenWithRemoteContent(), catalog).filter {
        it.severity == ComposeExportSeverity.ERROR
      }

    assertEquals(listOf("REMOTE_CONTENT_NOT_COMPOSE"), diagnostics.map { it.code })
    assertEquals("remote", diagnostics.single().nodeId)
    // The `layout/column` and `m3/text` inside the remote body are the same components the exporter
    // emits happily elsewhere, and the custom component has no Kotlin symbol at all. Reporting any
    // of them would be judging Remote Compose source by whether Compose can call it.
    assertTrue(diagnostics.none { it.nodeId == "custom" }, diagnostics.toString())
  }

  @Test
  fun `a custom component outside remote content is refused for the reason it is wrong`() {
    val stray =
      document(
        listOf(
          node("screen", "layout/column", "children" to listOf("intro", "custom", "outro")),
          text("intro", "Library"),
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
        )
      )

    val codes =
      CapabilityComposeCodeExporter.diagnose(stray, catalog)
        .filter { it.severity == ComposeExportSeverity.ERROR }
        .map { it.code }

    assertTrue("CUSTOM_COMPONENT_OUTSIDE_REMOTE_CONTENT" in codes, codes.toString())
  }

  @Test
  fun `the generated-code pane shows the remote body even when the screen refuses`() {
    val generated = UiBuilderEditorReducer(catalog).generatedCode(screenWithoutCustomComponent())

    val source = assertIs<EditorGeneratedCode.Source>(generated)
    assertTrue("@RemoteComposable" in source.kotlin, source.kotlin)
    assertTrue("RemoteColumn {" in source.kotlin, source.kotlin)
    assertTrue(
      "The screen around this content is not generated" in source.kotlin,
      "the screen's own refusal is kept rather than dropped: ${source.kotlin}",
    )
  }

  private fun screenWithRemoteContent(): UiBuilderDocument =
    document(
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
      )
    )

  private fun screenWithoutCustomComponent(): UiBuilderDocument =
    screenWithRemoteContent().let { design ->
      design.copy(
        nodes =
          design.nodes +
            ("remoteColumn" to
              design.nodes
                .getValue("remoteColumn")
                .copy(slots = mapOf("children" to listOf("remoteLabel"))))
      )
    }

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

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun document(nodes: List<UiBuilderNode>) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "inline-remote",
      title = "Player screen",
      revision = 3,
      catalogPin =
        JsonObject(
          mapOf(
            "systemId" to JsonPrimitive("m3-catalog"),
            "catalogRevision" to JsonPrimitive("candidate"),
            "capabilityDigest" to JsonPrimitive("candidate"),
            "nativeRuntimeId" to JsonPrimitive("candidate"),
          )
        ),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(412),
            "heightDp" to JsonPrimitive(892),
            "density" to JsonPrimitive(2.625),
            "theme" to JsonPrimitive("dark"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("screen"),
      nodes = nodes.associateBy { it.id },
    )
}
