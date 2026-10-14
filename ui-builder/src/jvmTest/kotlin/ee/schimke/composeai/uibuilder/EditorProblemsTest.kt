package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.codegen.ComposeExportSeverity
import ee.schimke.composeai.uibuilder.codegen.validateDocumentForExport
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.ExportDiagnosticCodes
import ee.schimke.composeai.uibuilder.export.MaterialIconArtifacts
import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.RootSurfaceGround
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.weatherWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The editor validates each write; this checks the panel reports problems with the document as a
 * whole before export does.
 */
class EditorProblemsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun problems(document: UiBuilderDocument) = reducer.problems(document)

  @Test
  fun `the checked-in fixture reports what the real export would refuse`() {
    // The panel also runs the server export's projection and generator, against which Jetcaster
    // doesn't export (enum values without Kotlin members, state comparisons, a text callback
    // consuming an event value, an adaptive grid spec).
    val reported = problems(document)
    assertTrue(reported.isNotEmpty(), "the export refuses this design; the panel should say so")
    assertTrue(
      reported.all { it.code == "COMPOSE_EXPORT_REFUSED" },
      "the fixture is structurally sound, so every problem should come from the export gate: $reported",
    )
    assertTrue(
      reported.any {
        it.message.contains("eventBindings.valueChange") && it.message.contains("callback")
      },
      reported.toString(),
    )
  }

  /**
   * The one problem that is not a refusal: a root surface's `containerColor` isn't the ground its
   * author assumed (see #485). The served export reports the same diagnostic.
   */
  @Test
  fun `a coloured root surface that does not fill the frame is a problem, and names the root`() {
    val root = document.nodes.getValue("root-surface")
    assertTrue(root.properties.containsKey("containerColor"), "the fixture's root is coloured")
    assertTrue(
      problems(document).none { it.code == RootSurfaceGround.CODE },
      "the fixture's root fills the frame, so there is nothing to say",
    )

    val wrapping =
      document.copy(
        nodes = document.nodes + ("root-surface" to root.copy(modifiers = JsonArray(emptyList())))
      )
    val reported = problems(wrapping).single { it.code == RootSurfaceGround.CODE }
    assertEquals("root-surface", reported.nodeId)
    assertEquals("m3/surface", reported.componentId)
    assertTrue("environment.theme" in reported.message, reported.message)
    // The export runs, so the panel must not sort this into the section headed "what the Compose
    // export gate refuses" or colour it as an error. Its own call site has always said as much.
    assertTrue(!reported.blocking, "the root-surface notice is not a refusal")
  }

  @Test
  fun `a wear widget is judged by the emitter that writes it, not by the record-driven gate`() {
    // A widget is written by `WearWidgetCodeExporter`/`RemoteContentEmitter`, not
    // `ScreenExportGate`; the panel must not report the gate's refusals (host frame "no component",
    // unmapped `center`) for it.
    val widget =
      weatherWidgetUiBuilderDocument("weather", document.catalogPin, document.environment)

    val reported = problems(widget).filter { it.code == "COMPOSE_EXPORT_REFUSED" }

    // Scoped to the export gate's code: the capability half still correctly reports the widget's
    // components missing from `m3-catalog`.
    assertEquals(emptyList(), reported, "this widget exports; the panel should refuse nothing")
    // And the pane and the panel agree, which is the property that broke: one said "here is your
    // widget's Kotlin" while the other said two of its nodes could not be written.
    assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(widget))
  }

  @Test
  fun `a widget's background picture exports from the bytes the editor fetched`() {
    // The panel and code pane run the emitter in the browser with the editor's fetched picture
    // bytes, as the server reads its asset store.
    val widget =
      weatherWidgetUiBuilderDocument("weather", document.catalogPin, document.environment)
    val rootId = widget.roots.single()
    val root = widget.nodes.getValue(rootId)
    val digest = "sha256:photo"
    val photo =
      UiBuilderNode(
        id = "photo",
        componentId = "asset/image",
        properties =
          JsonObject(
            mapOf(
              "assetKey" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("photo"))
                )
            )
          ),
      )
    val withPhoto =
      widget.copy(
        nodes =
          widget.nodes +
            ("photo" to photo) +
            (rootId to
              root.copy(
                slots =
                  root.slots + ("background" to (root.slots["background"].orEmpty() + "photo"))
              )),
        assets =
          JsonObject(
            widget.assets +
              ("photo" to
                Json.parseToJsonElement(
                  """{"mediaType":"image/png","contentDigest":"$digest",""" +
                    """"source":{"type":"uploaded","storageKey":"$digest"}}"""
                ))
          ),
      )
    fun refusedForBytes(assetBytes: (String) -> ByteArray?) =
      reducer.problems(withPhoto, assetBytes).filter {
        it.code == "COMPOSE_EXPORT_REFUSED" && "could not read" in it.message
      }

    assertTrue(refusedForBytes { null }.isNotEmpty(), "not fetched yet: honestly unreadable")
    val fetched =
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    val lookup = { key: String -> fetched.takeIf { key == digest } }
    assertEquals(emptyList(), refusedForBytes(lookup), "fetched: the panel refuses nothing")
    val code = assertIs<EditorGeneratedCode.Source>(reducer.generatedCode(withPhoto, lookup))
    assertTrue(".image(" in code.kotlin, code.kotlin.take(400))
  }

  @Test
  fun `a widget the emitter genuinely cannot write is still reported`() {
    // The panel isn't silenced for widgets: a second body in a container is a real refusal.
    val widget =
      weatherWidgetUiBuilderDocument("weather", document.catalogPin, document.environment)
    val rootId = widget.roots.single()
    val root = widget.nodes.getValue(rootId)
    val second =
      UiBuilderNode(
        id = "second",
        componentId = "m3/text",
        properties =
          JsonObject(
            mapOf(
              "text" to
                JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("2")))
            )
          ),
      )
    val twoBodies =
      widget.copy(
        nodes =
          widget.nodes +
            ("second" to second) +
            (rootId to
              root.copy(
                slots = root.slots + ("content" to (root.slots["content"].orEmpty() + "second"))
              ))
      )

    val reported = problems(twoBodies)

    assertTrue(
      reported.any { it.code == "COMPOSE_EXPORT_REFUSED" && "holds one body" in it.message },
      reported.toString(),
    )
  }

  /**
   * What the emitted widget carries beside its source is listed too, against its node, and never as
   * a blocker: the export still writes it (compose-preview-server #1666).
   */
  @Test
  fun `a widget export's diagnostics are listed as advisories on their node`() {
    val widget =
      weatherWidgetUiBuilderDocument("weather", document.catalogPin, document.environment)
    val rootId = widget.roots.single()
    val root = widget.nodes.getValue(rootId)
    val glyph =
      UiBuilderNode(
        id = "glyph",
        componentId = REMOTE_ICON_COMPONENT_ID,
        properties =
          JsonObject(
            mapOf(
              "imageVector" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive("pauseCircle"))
                )
            )
          ),
      )
    val withGlyph =
      widget.copy(
        nodes =
          widget.nodes +
            ("glyph" to glyph) +
            (rootId to root.copy(slots = root.slots + ("content" to listOf("glyph"))))
      )

    val reported =
      problems(withGlyph).single {
        it.code == ExportDiagnosticCodes.MATERIAL_ICONS_EXTENDED_REQUIRED
      }

    assertEquals("glyph", reported.nodeId)
    assertEquals(REMOTE_ICON_COMPONENT_ID, reported.componentId)
    assertTrue(!reported.blocking, "the export still writes the widget")
    assertTrue(MaterialIconArtifacts.EXTENDED_ARTIFACT in reported.message, reported.message)
  }

  @Test
  fun `a legacy padding override is not a problem, because the host shape owns the frame`() {
    val widget =
      weatherWidgetUiBuilderDocument("weather", document.catalogPin, document.environment)
    val rootId = widget.roots.single()
    val root = widget.nodes.getValue(rootId)
    val padded =
      widget.copy(
        nodes =
          widget.nodes +
            (rootId to
              root.copy(
                properties =
                  JsonObject(
                    root.properties +
                      ("horizontalPaddingDp" to
                        Json.parseToJsonElement("""{"type":"float","value":20.0}"""))
                  )
              ))
      )

    assertTrue(
      problems(padded).none { it.code == "COMPOSE_EXPORT_REFUSED" },
      problems(padded).toString(),
    )
  }

  @Test
  fun `a node missing a property its component requires is reported against that node`() {
    val placeholder = document.nodes.getValue("search-placeholder")
    val broken =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              placeholder.id to
                placeholder.copy(properties = JsonObject(placeholder.properties - "text"))
            )
      )

    val problem = assertNotNull(problems(broken).firstOrNull())
    assertEquals("MISSING_REQUIRED_PROPERTY", problem.code)
    assertEquals("search-placeholder", problem.nodeId)
    assertEquals("m3/text", problem.componentId)
  }

  @Test
  fun `a node no root can reach is reported, which no rejected write would have caught`() {
    // Nothing wrote to the icon. Its parent stopped naming it, which is the shape a botched delete
    // leaves behind and exactly what a per-edit check cannot see.
    val searchInput = document.nodes.getValue("search-input")
    val orphaned =
      document.copy(
        nodes =
          document.nodes +
            mapOf(searchInput.id to searchInput.copy(slots = searchInput.slots - "trailingIcon"))
      )

    val problem = assertNotNull(problems(orphaned).firstOrNull { it.code == "UNREACHABLE_NODE" })
    assertEquals("search-account-icon", problem.nodeId)
  }

  @Test
  fun `a problem naming a node that does not exist is not offered as something to select`() {
    // The row is clickable when it has a node. A dangling root has an id and no node behind it, and
    // selecting it would put the inspector on nothing.
    val ghostRoot = document.copy(roots = document.roots + "ghost")

    val problem = assertNotNull(problems(ghostRoot).firstOrNull { it.code == "UNKNOWN_ROOT" })
    assertNull(problem.nodeId)
    assertTrue(problem.message.contains("ghost"))
  }

  @Test
  fun `the panel reports exactly what the export gate refuses`() {
    // The panel reads the exporter rather than having its own rules, so it can't disagree with the
    // export.
    val placeholder = document.nodes.getValue("search-placeholder")
    val broken =
      document.copy(
        roots = document.roots + "ghost",
        nodes =
          document.nodes +
            mapOf(
              placeholder.id to
                placeholder.copy(properties = JsonObject(placeholder.properties - "text"))
            ),
      )

    // A superset: capability errors plus projection and generator refusals. Every capability error
    // must still appear.
    assertTrue(
      problems(broken)
        .map { it.code to it.message }
        .containsAll(
          CapabilityComposeCodeExporter.diagnose(broken, catalog)
            .filter { it.severity == ComposeExportSeverity.ERROR && it.code != "UNKNOWN_PROPERTY" }
            .map { it.code to it.message }
        )
    )
    // And nothing beyond the two sources: every problem is a capability error or an export
    // refusal, never a rule this panel invented.
    val capability =
      CapabilityComposeCodeExporter.diagnose(broken, catalog)
        .filter { it.severity == ComposeExportSeverity.ERROR && it.code != "UNKNOWN_PROPERTY" }
        .map { it.code to it.message }
        .toSet()
    assertTrue(
      problems(broken).all {
        (it.code to it.message) in capability ||
          it.code == "COMPOSE_EXPORT_REFUSED" ||
          it.code == "PROPERTY_NOT_DECLARED"
      },
      problems(broken).toString(),
    )
  }

  @Test
  fun `an undeclared property is preserved and can be explicitly dropped or mapped`() {
    val original = document.nodes.getValue("search-placeholder")
    val staleValue =
      JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("normal")))
    val stale =
      document.copy(
        nodes =
          document.nodes +
            (original.id to
              original.copy(
                properties = JsonObject(original.properties + ("oldText" to staleValue))
              ))
      )

    val problem = problems(stale).single { it.code == "PROPERTY_NOT_DECLARED" }
    assertEquals(original.id, problem.nodeId)
    assertEquals("oldText", problem.propertyName)
    assertTrue(!problem.blocking)
    val replacement = "fontStyle"
    assertTrue(replacement in problem.replacementProperties, problem.toString())

    val initial = reducer.initial(stale, original.id)
    val mapped =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.ResolveUndeclaredProperty(
          original.id,
          "oldText",
          replacement,
        ),
      )
    assertTrue(mapped.lastOutcome is CommandOutcome.Accepted, mapped.lastOutcome.toString())
    val mappedProperties = mapped.document.nodes.getValue(original.id).properties
    assertEquals(JsonPrimitive("normal"), mappedProperties[replacement]?.jsonObject?.get("value"))
    assertEquals(JsonPrimitive("enum"), mappedProperties[replacement]?.jsonObject?.get("type"))
    assertTrue("oldText" !in mappedProperties)

    val dropped =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.ResolveUndeclaredProperty(original.id, "oldText"),
      )
    assertTrue("oldText" !in dropped.document.nodes.getValue(original.id).properties)
  }

  @Test
  fun `something only the Compose projection refuses is reported too`() {
    // `remote-compose/document` is structurally valid but has no typed call emitter; the panel must
    // report it before export does.
    val player =
      UiBuilderNode(
        id = "player",
        componentId = "remote-compose/document",
        properties =
          JsonObject(
            mapOf(
              "documentBase64" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("AAAA"))
                )
            )
          ),
      )
    val onlyThePlayer = document.copy(roots = listOf(player.id), nodes = mapOf(player.id to player))

    assertEquals(
      emptyList(),
      validateDocumentForExport(onlyThePlayer, catalog),
      "the document itself is valid",
    )

    val reported = problems(onlyThePlayer)
    assertTrue(
      reported.any { it.code == "UNSUPPORTED_CODE_COMPONENT" && it.nodeId == player.id },
      "$reported",
    )
  }

  @Test
  fun `a mobile component with no Kotlin symbol is still reported against its node`() {
    // The Wear fix drops this diagnostic only where a dedicated emitter writes the design; a
    // refused Material 3 screen still points at the node.
    val unmapped =
      CapabilityCatalogParser.parse(
        Json.encodeToString(
          JsonObject.serializer(),
          Json.parseToJsonElement(resource("/m3-catalog-capabilities-v1.json")).jsonObject.let {
            catalogJson ->
            JsonObject(
              catalogJson +
                ("components" to
                  JsonArray(
                    catalogJson.getValue("components").jsonArray.map { component ->
                      val fields = component.jsonObject
                      if (fields["componentId"] == JsonPrimitive("m3/text"))
                        JsonObject(fields + ("code" to JsonNull))
                      else fields
                    }
                  ))
            )
          },
        )
      )

    val reported = UiBuilderEditorReducer(unmapped).problems(document)

    assertTrue(
      reported.any {
        it.code == "MISSING_CODE_CAPABILITY" &&
          it.componentId == "m3/text" &&
          it.nodeId != null &&
          it.blocking
      },
      reported.toString(),
    )
  }

  @Test
  fun `a warning the export proceeds through is not listed as a blocker`() {
    // The panel reports only what stops the build; warnings (like the floating toolbar's
    // dependency-floor note) are excluded.
    val diagnostics = CapabilityComposeCodeExporter.diagnose(document, catalog)
    assertTrue(
      diagnostics.any {
        it.code == "FLOATING_TOOLBAR_COMPATIBILITY_HELPER" &&
          it.severity == ComposeExportSeverity.WARNING
      },
      "the fixture should still raise the warning this test is about: $diagnostics",
    )

    // The claim is unchanged and still the point: a warning is not a blocker. What the panel does
    // list for this fixture are the export gate's refusals, which do stop a build.
    assertTrue(problems(document).none { it.code == "FLOATING_TOOLBAR_COMPATIBILITY_HELPER" })
  }

  @Test
  fun `a graph the panel exists to describe can still be drawn`() {
    // The navigator is built before the inspector, so an unknown child or a cycle must not crash it
    // before the panel can name either.
    val row = document.nodes.getValue("category-row")
    val dangling =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              row.id to
                row.copy(slots = row.slots + mapOf("items" to row.slots.getValue("items") + "gone"))
            )
      )

    val rows = reducer.treeRows(dangling)
    assertTrue(rows.none { it.nodeId == "gone" }, "a node that is not there is not a row")
    assertTrue(problems(dangling).any { it.code == "UNKNOWN_CHILD" }, "${problems(dangling)}")

    val cyclic =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              row.id to
                row.copy(slots = row.slots + mapOf("items" to row.slots.getValue("items") + row.id))
            )
      )

    assertTrue(reducer.treeRows(cyclic).isNotEmpty(), "a cycle terminates rather than recurses")
    assertTrue(problems(cyclic).isNotEmpty(), "and is reported")
  }

  @Test
  fun `a property encoded as the wrong shape is reported rather than thrown`() {
    // Building inspector fields for an `INVALID_PROPERTY_TYPE` node must not throw (`jsonPrimitive`
    // on an array).
    val placeholder = document.nodes.getValue("search-placeholder")
    val malformed =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              placeholder.id to
                placeholder.copy(
                  properties =
                    JsonObject(
                      placeholder.properties +
                        mapOf(
                          "text" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("string"),
                                "value" to JsonArray(listOf(JsonPrimitive("a"))),
                              )
                            )
                        )
                    )
                )
            )
      )

    val state = reducer.initial(malformed, selectedNodeId = placeholder.id)
    assertTrue(reducer.propertyFields(state).isNotEmpty(), "the inspector still has fields")
    assertTrue(reducer.treeRows(malformed).isNotEmpty())
    assertTrue(
      problems(malformed).any { it.code == "INVALID_PROPERTY_TYPE" },
      "${problems(malformed)}",
    )
  }

  @Test
  fun `an asset key encoded as the wrong shape is reported rather than thrown`() {
    // The editor asks for these diagnostics on every document now, so a throw in the export path's
    // asset-key decode became a way to take the whole editor down.
    val image = document.nodes.values.first { it.componentId == "asset/image" }
    val malformed =
      document.copy(
        nodes =
          document.nodes +
            mapOf(
              image.id to
                image.copy(
                  properties =
                    JsonObject(
                      image.properties +
                        mapOf(
                          "assetKey" to
                            JsonObject(
                              mapOf(
                                "type" to JsonPrimitive("string"),
                                "value" to JsonArray(emptyList()),
                              )
                            )
                        )
                    )
                )
            )
      )

    assertTrue(
      problems(malformed).any { it.code == "INVALID_PROPERTY_TYPE" },
      "${problems(malformed)}",
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
