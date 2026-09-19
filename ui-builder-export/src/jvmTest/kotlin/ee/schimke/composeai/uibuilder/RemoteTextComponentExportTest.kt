package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The published text component is written by the same hand as the borrowed one.
 *
 * `RemoteContentEmitter` has an authored case for `m3/text` that writes the whole typography a
 * design carries — a type scale as `RemoteMaterialTheme.typography.<role>`, a size, an alignment, a
 * colour as a literal or a theme role. The published `remote-m3/remote-text` is the same
 * `RemoteText` call, and without this it fell to the record fallback, whose vocabulary is six
 * scalar types and none of those: a design moving onto the published catalog arrived with its text
 * and lost the way it was set.
 *
 * The two spellings this writes are compiled rather than assumed, in the value vocabulary probe of
 * the repository that publishes the component.
 */
class RemoteTextComponentExportTest {

  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  @Test
  fun `the published component keeps the type scale, the size and the alignment`() {
    val refusals = mutableListOf<String>()
    val emitter =
      RemoteContentEmitter(
        document(
          REMOTE_TEXT_COMPONENT_ID,
          buildJsonObject {
            string("text", "Discover Weekly")
            string("style", "bodyMedium")
            string("textAlign", "center")
            string("color", "onSurface")
            number("fontSize", 17)
            putJsonObject("maxLines") {
              put("type", "int")
              put("value", 2)
            }
          },
        ),
        refusals,
        components = published,
      )

    val source = emitter.emit("label", 1).joinToString("\n")

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "style = RemoteMaterialTheme.typography.bodyMedium")
    assertContains(source, "fontSize = 17.rsp")
    assertContains(source, "textAlign = TextAlign.Center")
    assertContains(source, "color = RemoteMaterialTheme.colorScheme.onSurface")
    assertContains(source, "maxLines = 2")
  }

  /**
   * The borrowed spelling and the published one name the same thing.
   *
   * A design authored against `m3/text` says `fontSizeSp`; one against the published component says
   * what that catalog declares, which is `fontSize` while the properties come from `RemoteText`'s
   * own signature. One writer serves both, so neither design loses its size waiting for the catalog
   * to pick a spelling.
   */
  @Test
  fun `either spelling of the size is written`() {
    for (name in listOf("fontSizeSp", "fontSize")) {
      val refusals = mutableListOf<String>()
      val source =
        RemoteContentEmitter(
            document(
              REMOTE_TEXT_COMPONENT_ID,
              buildJsonObject {
                string("text", "hi")
                number(name, 22)
              },
            ),
            refusals,
            components = published,
          )
          .emit("label", 1)
          .joinToString("\n")

      assertTrue(refusals.isEmpty(), refusals.toString())
      // `message` by name: on the CharSequence overload the third positional parameter is
      // `ignoreCase`, not the message the Iterable one takes there.
      assertContains(source, "fontSize = 22.rsp", message = "`$name` is a size either way")
    }
  }

  /**
   * `rsp` is `val Int.rsp` and the library publishes no `Float.rsp`, so the obvious literal is
   * source that does not compile. This wrote `14.5f.rsp` for every design whose size was not whole.
   */
  @Test
  fun `a fractional size goes the long way round rather than emitting rsp`() {
    val refusals = mutableListOf<String>()
    val emitter =
      RemoteContentEmitter(
        document(
          "m3/text",
          buildJsonObject {
            string("text", "hi")
            number("fontSizeSp", 14.5)
          },
        ),
        refusals,
      )

    val source = emitter.emit("label", 1).joinToString("\n")

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "fontSize = 14.5f.sp.asRemoteTextUnit()")
    assertFalse("14.5f.rsp" in source, source)
    assertContains(
      emitter.imports(null),
      "androidx.compose.remote.creation.compose.state.asRemoteTextUnit",
    )
    assertContains(emitter.imports(null), "androidx.compose.ui.unit.sp")
  }

  /**
   * RemoteText needs no component record: its Kotlin spelling is the fixed Remote Material 3 API.
   *
   * Records cover discovered and pack components; catalog-owned Remote components do not flow
   * through that map to the editor's code pane. Requiring one here therefore made valid saved
   * remote-m3 documents refuse despite the catalog having offered the component.
   */
  @Test
  fun `the published component writes without an optional component record`() {
    val refusals = mutableListOf<String>()

    val source =
      RemoteContentEmitter(
          document(REMOTE_TEXT_COMPONENT_ID, buildJsonObject { string("text", "hi") }),
          refusals,
        )
        .emit("label", 1)
        .joinToString("\n")

    assertTrue(refusals.isEmpty(), refusals.toString())
    assertContains(source, "RemoteText(")
  }

  /** Present is all this needs to be: the writer reads the design, not the record. */
  private val published =
    mapOf(
      REMOTE_TEXT_COMPONENT_ID to
        ComponentRecord(
          canonicalId = "remote-catalog/RemoteText",
          componentIds = listOf(REMOTE_TEXT_COMPONENT_ID),
          symbol =
            ComponentSymbol(
              jvmOwner = "androidx.wear.compose.remote.material3.RemoteTextKt",
              callable = "androidx.wear.compose.remote.material3.RemoteText",
              name = "RemoteText",
              origin = ComponentOrigin.LIBRARY,
            ),
          parameters =
            listOf(
              TargetParameter(
                name = "text",
                type = "RemoteString",
                typeFqn = "androidx.compose.remote.creation.compose.state.RemoteString",
              )
            ),
          slots = emptyList(),
          signatureKnown = true,
        )
    )

  private fun kotlinx.serialization.json.JsonObjectBuilder.string(name: String, value: String) =
    putJsonObject(name) {
      put("type", "string")
      put("value", value)
    }

  private fun kotlinx.serialization.json.JsonObjectBuilder.number(name: String, value: Number) =
    putJsonObject(name) {
      put("type", if (value is Int) "int" else "float")
      put("value", value)
    }

  private fun document(componentId: String, properties: JsonObject): UiBuilderDocument =
    UiBuilderDocument(
      schema = "ui-builder-design-v1",
      id = "text",
      title = "Text",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("label"),
      nodes = mapOf("label" to UiBuilderNode("label", componentId, properties = properties)),
    )
}
