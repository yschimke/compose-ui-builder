package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class RemoteScopedSourceExportTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first {
        File(
            it,
            "docs/design/evidence/ui-builder-repetition-export/repetition-initial.document.json",
          )
          .isFile
      }

  private fun fixture() = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<UiBuilderDocument>(
      File(
          root,
          "docs/design/evidence/ui-builder-repetition-export/repetition-initial.document.json",
        )
        .readText()
    )

  private fun exported(document: UiBuilderDocument) =
    assertIs<InlineRemoteContentExporter.Result.Emitted>(
      InlineRemoteContentExporter.exportRoots(
        document,
        "proof.repetition.remote",
        emptyMap(),
        WidgetAssetBytes { null },
      )
    )

  private fun edit(
    document: UiBuilderDocument,
    id: String,
    change: (UiBuilderNode) -> UiBuilderNode,
  ) = document.copy(nodes = document.nodes + (id to change(document.nodes.getValue(id))))

  private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject

  private fun refused(document: UiBuilderDocument, reason: String) {
    val result =
      assertIs<InlineRemoteContentExporter.Result.Refused>(
        InlineRemoteContentExporter.exportRoots(
          document,
          null,
          emptyMap(),
          WidgetAssetBytes { null },
        )
      )
    assertTrue(result.reasons.any { reason in it }, result.reasons.toString())
  }

  @Test
  fun `production Remote source preserves loops definitions modifiers and callbacks`() {
    val result = exported(fixture())
    assertContains(result.source, ".forEach")
    assertEquals(1, Regex("private fun Pair\\(").findAll(result.source).count())
    assertContains(result.source, "argument0: kotlin.Float")
    assertContains(result.source, "RemoteArrangement.spacedBy(argument0.rdp)")
    assertContains(
      result.source,
      "capture0: androidx.compose.remote.creation.compose.action.Action",
    )
    assertContains(result.source, "valueChange(page, 10.ri)")
    assertContains(result.source, "RemoteBox(modifier = modifier)")
    val output =
      File(root, "experiments/remote-state-selection/build/scoped-projection-source/remote").apply {
        mkdirs()
      }
    File(output, "ExportedRemoteContent.kt").writeText(result.source)
    File(output, "ProofEntry.kt")
      .writeText(
        """
      package proof.repetition.remote
      import androidx.compose.runtime.Composable
      import androidx.compose.remote.creation.compose.layout.RemoteComposable
      @Composable @RemoteComposable
      fun RemoteRepeatedContent() { ${result.functionName}() }
    """
          .trimIndent()
      )
  }

  private fun boundFixture() = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<UiBuilderDocument>(
      File(root, "experiments/remote-state-selection/bound-actions.document.json").readText()
    )

  @Test
  fun `production Remote callbacks pass row values through renamed nested parameters`() {
    val result =
      assertIs<InlineRemoteContentExporter.Result.Emitted>(
        InlineRemoteContentExporter.exportRoots(
          boundFixture(),
          "proof.boundactions.remote",
          emptyMap(),
          WidgetAssetBytes { null },
        )
      )
    assertEquals(2, Regex("capture0: \\(kotlin.Int\\) ->").findAll(result.source).count())
    assertContains(result.source, "capture0 = capture0")
    assertContains(result.source, "clickable(capture0(argument1))")
    assertContains(result.source, "valueChange(page, actionValue0.ri)")
    val output =
      File(root, "experiments/remote-state-selection/build/bound-action-production-source/remote")
        .apply { mkdirs() }
    // Compile the exact production artifact through the same interaction proof as the prototype.
    File(output, "RemoteRepeatedContent.kt")
      .writeText(
        result.source +
          "\n" +
          "@Composable @RemoteComposable\nfun RemoteRepeatedContent() { ${result.functionName}() }\n"
      )
  }

  @Test
  fun `bound callbacks require every row to supply a value with the state type`() {
    refused(
      edit(boundFixture(), "inner-place") {
        it.copy(
          component =
            obj(
              """{"componentKey":"pair","arguments":{"spacing":{"type":"binding","value":"rowGap"},"destination":{"type":"string","value":"wrong"}}}"""
            )
        )
      },
      "expected kotlin.Int",
    )
    refused(
      edit(boundFixture(), "red") {
        it.copy(
          eventBindings =
            obj(
              """{"click":[{"type":"set","variable":"page","value":{"type":"binding","value":"missing"}}]}"""
            )
        )
      },
      "missing component argument",
    )
    refused(
      edit(boundFixture(), "red") {
        it.copy(
          eventBindings =
            obj(
              """{"click":[{"type":"set","variable":"page","value":{"type":"binding","value":"spacing"}}]}"""
            )
        )
      },
      "used as both",
    )
    refused(boundFixture().copy(roots = listOf("red")), "lexical binding")
  }

  @Test
  fun `bound and literal actions retain order in a single factory`() {
    val document =
      edit(boundFixture(), "red") {
        it.copy(
          eventBindings =
            obj(
              """{"click":[{"type":"set","variable":"page","value":{"type":"binding","value":"destination"}},{"type":"set","variable":"page","value":10}]}"""
            )
        )
      }
    assertContains(
      exported(document).source,
      "combinedAction(valueChange(page, actionValue0.ri), valueChange(page, 10.ri))",
    )
  }

  @Test
  fun `action factories use the destination scalar type and check every supplied operand`() {
    val output =
      File(root, "experiments/remote-state-selection/build/bound-action-production-source/remote")
        .apply { mkdirs() }
    for ((kind, literal, type) in
      listOf(
        Triple("int", "-27", "kotlin.Int"),
        Triple("float", "2.5", "kotlin.Float"),
        Triple("bool", "true", "kotlin.Boolean"),
        Triple("string", "\"chosen\"", "kotlin.String"),
      )) {
      val base = boundFixture()
      val document =
        edit(
            edit(base, "red") {
              it.copy(
                eventBindings =
                  obj(
                    """{"click":[{"type":"set","variable":"chosen","value":{"type":"binding","value":"destination"}}]}"""
                  )
              )
            },
            "inner-place",
          ) {
            it.copy(
              component =
                obj(
                  """{"componentKey":"pair","arguments":{"spacing":{"type":"binding","value":"rowGap"},"destination":{"type":"$kind","value":$literal}}}"""
                )
            )
          }
          .let {
            // selectedItem is no longer used by this variant; retain only the spacing argument/row
            // field.
            edit(it, "place") { node ->
              node.copy(
                component =
                  obj(
                    """{"componentKey":"forward","arguments":{"rowGap":{"type":"binding","value":"gap"}}}"""
                  )
              )
            }
          }
          .let {
            it.copy(
              stateVariables =
                JsonObject(
                  it.stateVariables +
                    ("chosen" to
                      obj(
                        """{"type":"value","valueType":"$kind","initialValue":$literal,"nullable":false}"""
                      ))
                )
            )
          }
      val result =
        assertIs<InlineRemoteContentExporter.Result.Emitted>(
          InlineRemoteContentExporter.exportRoots(
            document,
            "proof.boundactions.types.$kind",
            emptyMap(),
            WidgetAssetBytes { null },
          )
        )
      assertContains(
        result.source,
        "($type) -> androidx.compose.remote.creation.compose.action.Action",
      )
      File(output, "${kind}Actions.kt").writeText(result.source)
    }
  }

  @Test
  fun `direct loop actions read their own row without a component factory`() {
    val base = boundFixture()
    val document =
      edit(edit(base, "loop") { it.copy(slots = mapOf("template" to listOf("red"))) }, "red") {
        it.copy(
          eventBindings =
            obj(
              """{"click":[{"type":"set","variable":"page","value":{"type":"binding","value":"itemId"}}]}"""
            )
        )
      }
    assertContains(exported(document).source, "clickable(valueChange(page, uiRow0.argument0.ri))")
  }

  @Test
  fun `empty rows keep a typed template and still validate it`() {
    val empty =
      edit(fixture(), "loop") {
        it.copy(
          properties =
            JsonObject(it.properties + ("data" to obj("""{"type":"list","values":[]}""")))
        )
      }
    val source = exported(empty).source
    assertContains(source, "data class UiRows0(val argument0: kotlin.Float)")
    assertContains(source, "listOf<UiRows0>().forEach")
    refused(
      edit(empty, "place") {
        it.copy(component = obj("""{"componentKey":"pair","arguments":{}}"""))
      },
      "missing component argument",
    )
    refused(empty.copy(components = obj("""{"pair":{"name":"Pair","root":"place"}}""")), "cyclic")
  }

  @Test
  fun `invalid rows and mismatched values refuse without dropping data`() {
    for ((data, reason) in
      listOf(
        """{"type":"state","variable":"rows"}""" to "authored row list",
        """{"type":"list","values":[{"type":"object","fields":{}}]}""" to "missing row field",
        """{"type":"list","values":[{"type":"object","fields":{"gap":{"type":"string","value":"wide"}}}]}""" to
          "expected kotlin.Float",
      )) {
      refused(
        edit(fixture(), "loop") {
          it.copy(properties = JsonObject(it.properties + ("data" to obj(data))))
        },
        reason,
      )
    }
  }

  @Test
  fun `component and loop scopes do not implicitly fall back to another instance`() {
    refused(fixture().copy(roots = listOf("row")), "lexical binding")
    refused(
      edit(fixture(), "place") {
        it.copy(
          component =
            obj("""{"componentKey":"pair","arguments":{"other":{"type":"float","value":3}}}""")
        )
      },
      "unknown component argument",
    )
  }

  @Test
  fun `nested components forward explicit callbacks and arguments`() {
    val base = fixture()
    val middle =
      UiBuilderNode(
        "middle",
        "design/component-instance",
        component =
          obj(
            """{"componentKey":"inner","arguments":{"spacing":{"type":"binding","value":"spacing"}}}"""
          ),
      )
    val nested =
      base.copy(
        nodes = base.nodes + (middle.id to middle),
        components =
          obj("""{"pair":{"name":"Pair","root":"middle"},"inner":{"name":"Inner","root":"row"}}"""),
      )
    val source = exported(nested).source
    assertContains(source, "private fun Pair(")
    assertContains(source, "private fun Inner(")
    assertContains(source, "capture0 = capture0")
    assertEquals(1, Regex("valueChange\\(page, 10.ri\\)").findAll(source).count())
  }

  @Test
  fun `nested row initializers can read outer rows and template reads stay local`() {
    val base = fixture()
    val inner =
      base.nodes
        .getValue("loop")
        .copy(
          id = "inner",
          properties =
            obj(
              """{"data":{"type":"list","values":[{"type":"object","fields":{"gap":{"type":"binding","value":"gap"}}}]}}"""
            ),
        )
    val nested =
      edit(base, "loop") { it.copy(slots = mapOf("template" to listOf("inner"))) }
        .let { it.copy(nodes = it.nodes + (inner.id to inner)) }
    val source = exported(nested).source
    assertContains(source, "UiRows1(uiRow0.argument0)")
    assertContains(source, "argument0 = uiRow1.argument0")
  }

  @Test
  fun `loop and placement events cannot disappear during source lowering`() {
    for (id in listOf("loop", "place")) {
      refused(
        edit(fixture(), id) { it.copy(eventBindings = obj("""{"longClick":[]}""")) },
        "nodes.$id.eventBindings.longClick",
      )
      refused(
        edit(fixture(), id) { it.copy(eventBindings = obj("""{"click":{}}""")) },
        "nodes.$id.eventBindings.click",
      )
      val source =
        exported(
            edit(fixture(), id) {
              it.copy(eventBindings = fixture().nodes.getValue("red").eventBindings)
            }
          )
          .source
      assertContains(source, "clickable(valueChange(page, 10.ri))")
    }
  }

  @Test
  fun `unsafe names and unsupported modifier bindings cannot become source`() {
    refused(
      fixture().copy(components = obj("""{"pair":{"name":"RemoteBox","root":"row"}}""")),
      "conflicts",
    )
    refused(
      fixture().copy(components = obj("""{"pair":{"name":"Pair() {}","root":"row"}}""")),
      "invalid",
    )
    refused(
      edit(fixture(), "red") {
        it.copy(
          modifiers =
            Json.parseToJsonElement(
                """[{"type":"width","widthDp":{"type":"binding","value":"gap"}}]"""
              )
              .jsonArray
        )
      },
      "modifier bindings",
    )
  }
}
