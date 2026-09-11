package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/** The Android proof consumes these exact production exports, never a hand-written equivalent. */
class RemoteStateSelectionExportTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private fun document(
    kind: String,
    values: List<JsonPrimitive>,
    fallback: Boolean = true,
  ): UiBuilderDocument {
    val selection =
      StateSelection(
        buildJsonObject {
          put("type", "state")
          put("variable", "page")
        },
        values.mapIndexed { index, value -> "case$index" to value }.toMap(),
        if (fallback) "other" else null,
      )
    val ids = selection.cases.keys.toList() + listOfNotNull(selection.fallback)
    val nodes =
      listOf(
        UiBuilderNode(
          "remote",
          REMOTE_COMPOSE_INLINE_COMPONENT_ID,
          slots = mapOf("content" to listOf("switch")),
        ),
        UiBuilderNode(
          "switch",
          "layout/box",
          properties = buildJsonObject { put(SHOW_BY_STATE, selection.encode()) },
          modifiers =
            buildJsonArray {
              add(
                buildJsonObject {
                  put("type", "padding")
                  put("startDp", 8)
                  put("topDp", 8)
                  put("endDp", 8)
                  put("bottomDp", 8)
                }
              )
            },
          slots = mapOf("children" to ids),
        ),
      ) +
        ids.mapIndexed { index, id ->
          UiBuilderNode(
            id,
            "layout/box",
            modifiers =
              buildJsonArray {
                add(
                  buildJsonObject {
                    put("type", "size")
                    put("widthDp", 40 + index * 10)
                    put("heightDp", 40 + index * 10)
                  }
                )
              },
          )
        }
    return UiBuilderDocument(
      "ui-builder-design-v1",
      "selection",
      "Selection",
      1,
      JsonObject(emptyMap()),
      JsonObject(emptyMap()),
      buildJsonObject {
        putJsonObject("page") {
          put("valueType", kind)
          put("initialValue", values.first())
        }
      },
      listOf("remote"),
      nodes.associateBy { it.id },
    )
  }

  @Test
  fun `exported selectors compile and play in the standalone Android proof`() {
    val scenarios =
      mapOf(
        "Integer" to document("int", listOf(JsonPrimitive(10), JsonPrimitive(20))),
        "Boolean" to document("bool", listOf(JsonPrimitive(false), JsonPrimitive(true))),
        "Decimal" to document("float", listOf(JsonPrimitive(-1.5f), JsonPrimitive(2.5f))),
        "AdjacentDecimal" to
          document(
            "float",
            listOf(JsonPrimitive(1f), JsonPrimitive(Float.fromBits(1f.toRawBits() + 1))),
          ),
        "NoFallback" to document("int", listOf(JsonPrimitive(10), JsonPrimitive(20)), false),
        "ManyCases" to document("int", (10..130 step 10).map(::JsonPrimitive)),
        "Extremes" to
          document("int", listOf(JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(Int.MAX_VALUE))),
      )
    val output = File("build/remote-state-selection").apply { mkdirs() }
    scenarios.forEach { (name, document) ->
      val emitted =
        assertIs<InlineRemoteContentExporter.Result.Emitted>(
          InlineRemoteContentExporter.export(
            document.copy(title = name),
            "remote",
            "proof.generated",
          )
        )
      assertContains(emitted.source, "RemoteStateLayout(")
      assertContains(emitted.source, "val page = rememberMutableRemote")
      assertContains(emitted.source, "RemoteModifier.padding(")
      assertContains(emitted.source, ".createReference()")
      File(output, "$name.kt").writeText(emitted.source)
    }
  }

  @Test
  fun `unsupported nullable and string selectors report located reasons`() {
    val string = document("string", listOf(JsonPrimitive("first"), JsonPrimitive("second")))
    val refusal =
      assertIs<InlineRemoteContentExporter.Result.Refused>(
        InlineRemoteContentExporter.export(string, "remote")
      )
    assertTrue(refusal.reasons.any { "switch" in it && "String equality" in it })
    val numeric = document("int", listOf(JsonPrimitive(10)))
    val nullable =
      numeric.copy(
        stateVariables =
          buildJsonObject {
            putJsonObject("page") {
              put("valueType", "int")
              put("initialValue", JsonNull)
              put("nullable", true)
            }
          }
      )
    assertIs<InlineRemoteContentExporter.Result.Refused>(
      InlineRemoteContentExporter.export(nullable, "remote")
    )
  }

  @Test
  fun `record state reads and writes share one declaration with the selector`() {
    val base = document("bool", listOf(JsonPrimitive(false), JsonPrimitive(true)))
    val control =
      UiBuilderNode(
        "control",
        "catalog/control",
        properties =
          buildJsonObject {
            putJsonObject("enabled") {
              put("type", "state")
              put("variable", "page")
            }
          },
        eventBindings =
          buildJsonObject {
            putJsonArray("click") {
              add(
                buildJsonObject {
                  put("type", "toggle")
                  put("variable", "page")
                }
              )
            }
          },
      )
    val record =
      ComponentRecord(
        canonicalId = "catalog/control",
        componentIds = emptyList(),
        symbol =
          ComponentSymbol(
            jvmOwner = "example.ControlKt",
            callable = "example.Control",
            name = "Control",
            origin = ComponentOrigin.LIBRARY,
          ),
        parameters =
          listOf(
            TargetParameter(
              name = "modifier",
              type = "RemoteModifier",
              typeFqn = "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
              hasDefault = true,
            ),
            TargetParameter(
              name = "enabled",
              type = "RemoteBoolean",
              typeFqn = "androidx.compose.remote.creation.compose.state.RemoteBoolean",
            ),
            TargetParameter(
              name = "onClick",
              type = "Action",
              typeFqn = "androidx.compose.remote.creation.compose.action.Action",
            ),
          ),
        slots = emptyList(),
        signatureKnown = true,
      )
    for (defaulted in listOf(false, true)) {
      val refusals = mutableListOf<String>()
      val withDefault =
        record.copy(
          parameters =
            record.parameters.map { parameter ->
              if (parameter.name == "onClick") parameter.copy(hasDefault = defaulted) else parameter
            }
        )
      val emitter =
        RemoteContentEmitter(
          base.copy(nodes = base.nodes + ("control" to control)),
          refusals,
          components = mapOf(control.componentId to withDefault),
        )
      emitter.emit("switch", 1)
      val source = emitter.emit("control", 1).joinToString("\n")
      assertTrue(refusals.isEmpty(), refusals.toString())
      assertContains(source, "enabled = page")
      assertContains(source, "onClick = valueChange(page, !page)")
      assertFalse(".clickable(" in source, source)
      assertEquals(listOf("val page = rememberMutableRemoteBoolean(false)"), emitter.stateLocals())
    }
  }

  @Test
  fun `inline text binding declares its mutable and rejects an incompatible declaration`() {
    val base = document("string", listOf(JsonPrimitive("Ready")))
    val text =
      UiBuilderNode(
        "label",
        "m3/text",
        properties =
          buildJsonObject {
            putJsonObject("text") {
              put("type", "state")
              put("variable", "page")
            }
          },
      )
    val remote = base.nodes.getValue("remote").copy(slots = mapOf("content" to listOf("label")))
    val doc = base.copy(nodes = mapOf("remote" to remote, "label" to text))
    val source =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(
          InlineRemoteContentExporter.export(doc, "remote")
        )
        .source
    assertContains(source, "val page = rememberMutableRemoteString(\"Ready\")")
    assertContains(source, "text = page")
    val wrong = doc.copy(stateVariables = document("int", listOf(JsonPrimitive(10))).stateVariables)
    assertIs<InlineRemoteContentExporter.Result.Refused>(
      InlineRemoteContentExporter.export(wrong, "remote")
    )
  }
}
