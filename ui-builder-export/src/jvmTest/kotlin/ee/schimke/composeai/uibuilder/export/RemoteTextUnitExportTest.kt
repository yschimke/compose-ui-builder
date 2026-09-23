package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A text size crosses into Remote Compose as `<Int>.rsp`, or not at all.
 *
 * The server now derives `fontSize` as a `number` property, so a design can carry an authored size
 * onto a published Remote Compose text component. That is only honest if the generator can write
 * one: a property that is authorable, drawable and unexportable is the shape of
 * [#508](https://github.com/yschimke/compose-preview-server/issues/508), and the reason the record
 * call refuses by name rather than guessing.
 *
 * `22.rsp` is compiled rather than assumed — the value vocabulary probe in the catalog repository
 * that publishes the component holds that claim. What this file adds is the other half: that the
 * emitter writes exactly that, and refuses the sizes the spelling cannot express.
 */
class RemoteTextUnitExportTest {

  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  @Test
  fun `a whole text size is written as rsp`() {
    val refusals = mutableListOf<String>()
    val emitter = emitter(size = 22, refusals = refusals)

    val source = emitter.emit("label", 1).joinToString("\n")

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "fontSize = 22.rsp")
    assertContains(
      emitter.imports(null),
      "androidx.compose.remote.creation.compose.state.rsp",
      "the spelling only compiles with its extension imported",
    )
  }

  /** Rounding would be this generator choosing a size the author did not. */
  @Test
  fun `a fractional text size is refused rather than rounded`() {
    val refusals = mutableListOf<String>()

    val source = emitter(size = 14.5, refusals = refusals).emit("label", 1).joinToString("\n")

    assertFalse("rsp" in source, source)
    assertEquals(1, refusals.size, refusals.toString())
    assertTrue(refusals.single().contains("`<Int>.rsp`"), refusals.single())
    assertTrue(refusals.single().contains("14.5"), refusals.single())
  }

  private fun emitter(size: Number, refusals: MutableList<String>): RemoteContentEmitter =
    RemoteContentEmitter(
      document(size),
      refusals,
      components = mapOf("catalog/text" to record),
    )

  private fun document(size: Number): UiBuilderDocument {
    val label =
      UiBuilderNode(
        "label",
        "catalog/text",
        properties =
          buildJsonObject {
            putJsonObject("text") {
              put("type", "string")
              put("value", "Discover Weekly")
            }
            putJsonObject("fontSize") {
              put("type", if (size is Int) "int" else "float")
              put("value", size)
            }
          },
      )
    return UiBuilderDocument(
      schema = "ui-builder-design-v1",
      id = "sized",
      title = "Sized",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("label"),
      nodes = mapOf("label" to label),
    )
  }

  /** `RemoteText`'s shape, as the published record states it. */
  private val record =
    ComponentRecord(
      canonicalId = "catalog/text",
      componentIds = emptyList(),
      symbol =
        ComponentSymbol(
          jvmOwner = "example.RemoteTextKt",
          callable = "example.RemoteText",
          name = "RemoteText",
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters =
        listOf(
          TargetParameter(
            name = "text",
            type = "RemoteString",
            typeFqn = "androidx.compose.remote.creation.compose.state.RemoteString",
          ),
          TargetParameter(
            name = "fontSize",
            type = "RemoteTextUnit",
            typeFqn = "androidx.compose.remote.creation.compose.state.RemoteTextUnit",
            hasDefault = true,
          ),
        ),
      slots = emptyList(),
      signatureKnown = true,
    )
}
