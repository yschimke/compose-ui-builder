package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/** Real shared exports: also consumed by the compiled desktop interaction proof. */
class ScopedComposeProjectionTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val scopedGeneratorAvailable = runCatching {
    Json.decodeFromString<ee.schimke.composeai.discovery.ScreenDocument>(
      """{"name":"Probe","functions":[],"root":{"componentId":"","repetition":{"fields":{},"rows":[]}}}"""
    )
  }
    .isSuccess
  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }
  /**
   * The m3 record plus the builder's own `layout/`, `shape/` and `asset/` components.
   *
   * Two files because they are two records: `androidx.compose.foundation` publishes one `Column`
   * rather than one per design system, so it belongs to no catalog and lives in
   * `compose-foundation-components-v1.json`. The fixture this test projects is mostly layout, so
   * reading the m3 one alone would exercise an export that refuses every node in it.
   *
   * The skip is the server's, rule for rule — `ComponentRecordSource.withFoundation` drops a
   * foundation entry whose canonical id OR whose component id the catalog already claims, and a
   * projection judged against a looser record would see an id claimed twice and refuse what the
   * server writes. This module cannot see that class, so the rule is restated rather than shared;
   * the two files share neither key today, and a rule that only holds while that is true is not the
   * rule.
   */
  private val records = Json {
    ignoreUnknownKeys = true
  }
    .let { json ->
      fun read(name: String) =
        json.decodeFromString<ComponentRecordFile>(
          File(root, "docs/design/fixtures/ui-builder/$name").readText()
        )
      val m3 = read("m3-catalog-components-v1.json")
      val taken = m3.components.map { it.canonicalId }.toSet()
      val claimed = m3.components.flatMapTo(mutableSetOf()) { it.componentIds }
      val extra =
        read("compose-foundation-components-v1.json").components.filterNot { candidate ->
          candidate.canonicalId in taken || candidate.componentIds.any { it in claimed }
        }
      m3.copy(components = m3.components + extra)
    }

  private fun fixture() =
    Json.decodeFromString<DesignDocumentV1>(
      File(
          root,
          "docs/design/evidence/ui-builder-repetition-export/repetition-initial.uid",
        )
        .readText()
    )

  private fun source(document: DesignDocumentV1): String? {
    val projected = ScreenDocumentProjection.project(document, "ComposeRepeatedContent")
    // The published floor must remain usable: only new constructs refuse without local staging.
    if (!scopedGeneratorAvailable) {
      assertTrue(
        assertIs<ScreenDocumentProjection.Outcome.Refused>(projected).reasons.any {
          "shared generator support" in it
        }
      )
      return null
    }
    val screen =
      assertIs<ScreenDocumentProjection.Outcome.Projected>(projected, projected.toString()).document
    val generated =
      ScreenGenerator.generate(
        screen,
        records,
        "proof.repetition.compose",
        expressionPackages = ScreenExportGate.EXPRESSION_PACKAGES,
      )
    return assertIs<ScreenGenerator.Result.Emitted>(generated, generated.toString()).source
  }

  private fun edit(document: DesignDocumentV1, id: String, change: (DesignNodeV1) -> DesignNodeV1) =
    document.copy(nodes = document.nodes + (id to change(document.nodes.getValue(id))))

  private fun refused(document: DesignDocumentV1, message: String) {
    val result =
      assertIs<ScreenDocumentProjection.Outcome.Refused>(ScreenDocumentProjection.project(document))
    if (!scopedGeneratorAvailable) return
    assertTrue(result.reasons.any { message in it }, result.reasons.toString())
  }

  @Test
  fun `semantic fixture exports real loops functions modifiers state and callbacks`() {
    val source = source(fixture()) ?: return
    assertContains(source, ".forEach")
    assertContains(source, "private fun Pair(")
    assertContains(source, "argument0: kotlin.Float")
    assertContains(source, "capture0: () -> kotlin.Unit")
    assertContains(source, "capture1: () -> kotlin.Unit")
    assertContains(source, "Arrangement.spacedBy(argument0.dp)")
    assertContains(source, "page.value = 10")
    assertContains(source, "page.value = 20")
    assertEquals(1, Regex("private fun Pair").findAll(source).count())
    val output =
      File(root, "experiments/remote-state-selection/build/scoped-projection-source/compose")
        .apply { mkdirs() }
    File(output, "ComposeRepeatedContent.kt").writeText(source)
    val browser = ScreenExportGate.export(fixture(), records)
    assertIs<ScreenExportGate.Outcome.Emitted>(browser, browser.toString())
    assertEquals(
      source
        .replace("package proof.repetition.compose", "package ${ScreenExportGate.PACKAGE_NAME}")
        .replace(
          "fun ComposeRepeatedContent()",
          "fun ${ScreenDocumentProjection.screenNameFor(fixture())}()",
        ),
      browser.source,
    )
  }

  @Test
  fun `empty data keeps typed template and validates missing bindings and cycles`() {
    val empty =
      edit(fixture(), "loop") {
        it.copy(properties = it.properties + ("data" to ListValueV1(emptyList())))
      }
    val source = source(empty) ?: return
    assertContains(source, ".forEach")
    assertContains(source, "Float")
    assertContains(source, "private fun Pair(")
    refused(
      edit(empty, "place") { it.copy(component = it.component!!.copy(arguments = emptyMap())) },
      "missing component argument",
    )
    refused(
      empty.copy(components = empty.components.mapValues { it.value.copy(root = "place") }),
      "contains itself",
    )
  }

  @Test
  fun `row field and component parameter types are checked at actual uses`() {
    val wrong =
      edit(fixture(), "loop") {
        it.copy(
          properties =
            it.properties +
              ("data" to ListValueV1(listOf(ObjectValueV1(mapOf("gap" to StringValueV1("wide"))))))
        )
      }
    refused(wrong, "expected kotlin.Float")
    val missing =
      edit(fixture(), "loop") {
        it.copy(
          properties = it.properties + ("data" to ListValueV1(listOf(ObjectValueV1(emptyMap()))))
        )
      }
    refused(missing, "missing row field `gap`")
    refused(
      edit(fixture(), "place") {
        it.copy(component = it.component!!.copy(arguments = mapOf("unknown" to IntegerValueV1(1))))
      },
      "has no argument `unknown`",
    )
  }

  @Test
  fun `bindings outside lexical scopes refuse instead of capturing another placement`() {
    refused(fixture().copy(roots = listOf("row")), "outside a component or loop")
    val missing =
      edit(fixture(), "place") { it.copy(component = it.component!!.copy(arguments = emptyMap())) }
    refused(missing, "missing component argument `spacing`")
  }

  @Test
  fun `nested component calls forward state and callbacks explicitly`() {
    val base = fixture()
    val middle =
      DesignNodeV1(
        "middle",
        "design/component-instance",
        component =
          DesignComponentInstanceV1("inner", mapOf("spacing" to BindingValueV1("outerSpacing"))),
      )
    val nested =
      edit(base, "place") {
          it.copy(
            component =
              DesignComponentInstanceV1("pair", mapOf("outerSpacing" to BindingValueV1("gap")))
          )
        }
        .let {
          it.copy(
            nodes = it.nodes + ("middle" to middle),
            components =
              it.components +
                mapOf(
                  "pair" to DesignComponentV1("Pair", "middle"),
                  "inner" to DesignComponentV1("Inner", "row"),
                ),
          )
        }
    val source = source(nested) ?: return
    assertContains(source, "private fun Pair(")
    assertContains(source, "private fun Inner(")
    assertContains(source, "capture0 = capture0")
    assertEquals(1, Regex("page.value = 10").findAll(source).count())
  }

  @Test
  fun `function state reads become value parameters instead of implicit screen captures`() {
    val base = fixture()
    val text =
      DesignNodeV1("stateLabel", "m3/text", properties = mapOf("text" to StateValueV1("label")))
    val withState =
      edit(base, "row") {
          it.copy(slots = mapOf("children" to listOf("red", "green", "stateLabel")))
        }
        .let {
          it.copy(
            nodes = it.nodes + (text.id to text),
            stateVariables =
              it.stateVariables +
                ("label" to
                  StateVariableV1(
                    type = StateVariableTypeV1.VALUE,
                    persistence = StatePersistenceV1.PREVIEW,
                    initialValue = JsonPrimitive("Ready"),
                    valueType = StateValueTypeV1.STRING,
                  )),
          )
        }
    val source = source(withState) ?: return
    assertContains(source, "capture2: kotlin.String")
    assertContains(source, "capture2 = label.value")
    assertContains(source, "text = capture2")
  }

  @Test
  fun `nested loops bind their own rows while initializers can read the outer row`() {
    val base = fixture()
    val inner =
      base.nodes
        .getValue("loop")
        .copy(
          id = "inner",
          properties =
            mapOf(
              "data" to ListValueV1(listOf(ObjectValueV1(mapOf("gap" to BindingValueV1("gap")))))
            ),
        )
    val nested =
      edit(base, "loop") { it.copy(slots = mapOf("template" to listOf("inner"))) }
        .let { it.copy(nodes = it.nodes + (inner.id to inner)) }
    val source = source(nested) ?: return
    assertEquals(2, Regex("\\.forEach").findAll(source).count())
    assertContains(source, "ScreenRow_1(screenRow.field0)")
    assertContains(source, "argument0 = screenRow_1.field0")
  }

  @Test
  fun `bound spacing combines with the authored alignment`() {
    val doc =
      edit(fixture(), "row") {
        it.copy(properties = it.properties + ("horizontalArrangement" to EnumValueV1("center")))
      }
    val source = source(doc) ?: return
    assertContains(source, "max(0.0f, argument0).dp")
    assertContains(source, "Alignment.CenterHorizontally")
  }

  @Test
  fun `a binding cannot become both a string and a number`() {
    val base = fixture()
    val text =
      DesignNodeV1("label", "m3/text", properties = mapOf("text" to BindingValueV1("spacing")))
    val conflict =
      edit(base, "row") { it.copy(slots = mapOf("children" to listOf("label"))) }
        .let { it.copy(nodes = it.nodes + (text.id to text)) }
    refused(conflict, "both kotlin.Float and kotlin.String")
  }
}
