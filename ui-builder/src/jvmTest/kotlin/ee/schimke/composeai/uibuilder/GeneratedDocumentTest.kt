package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.accepts
import ee.schimke.composeai.uibuilder.codegen.validateDocumentForExport
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.REMOTE_COMPOSE_INLINE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.VariableFontText
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Can the editor produce every tree its own catalog says is legal, and is everything it produces
 * valid all the way to Kotlin?
 *
 * [UiBuilderTreeGenerator] walks the catalog as a grammar and builds documents by the same
 * select-and-Add a person uses. Assertions cover structure (one root, no dangling ids, single
 * placement, no cycles, cardinality), catalog rules (slot acceptance, required properties, enum
 * values, modifiers), export, and round trip.
 *
 * Seeds are fixed so failures reproduce. The first test is exhaustive for one insert from a real
 * document.
 */
class GeneratedDocumentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val validator = CapabilityValidator(catalog)
  private val generator = UiBuilderTreeGenerator(catalog, reducer)
  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val blank =
    blankUiBuilderDocument("generated", jetcaster.catalogPin, jetcaster.environment)

  /** A generated document that contains at least one `m3/button`. */
  private val valid: UiBuilderDocument by lazy {
    val box = blank.nodes.values.first { it.componentId == "layout/box" }.id
    val state = reducer.reduce(reducer.initial(blank), UiBuilderEditorEvent.SelectNode(box))
    val target = checkNotNull(reducer.dropTarget(state, "m3/button"))
    reducer.reduce(state, UiBuilderEditorEvent.InsertComponent("m3/button", target)).document
  }

  /**
   * Every offer the palette makes is one the reducer takes, leaving a valid document. Exhaustive:
   * every node of the blank template against every catalog component.
   */
  @Test
  fun `every offer the palette makes is an insert that leaves a valid document`() {
    var offers = 0
    blank.nodes.keys.forEach { nodeId ->
      catalog.components.forEach { component ->
        val state = reducer.reduce(reducer.initial(blank), UiBuilderEditorEvent.SelectNode(nodeId))
        val target = reducer.dropTarget(state, component.componentId) ?: return@forEach
        offers++
        val inserted =
          reducer.reduce(
            state,
            UiBuilderEditorEvent.InsertComponent(component.componentId, target),
          )
        assertIs<CommandOutcome.Accepted>(
          inserted.lastOutcome,
          "${component.componentId} offered ${target.nodeId}.${target.slot} with $nodeId " +
            "selected, then refused: ${inserted.lastOutcome}",
        )
        val case = "${component.componentId} into ${target.slot}"
        assertStructurallyValid(inserted.document, case)
        assertExportAgreesWithTheRecord(inserted.document, case)
      }
    }
    assertTrue(offers > 40, "expected the blank template to offer real coverage, got $offers")
  }

  @Test
  fun `generated trees are valid, exportable and round trip`() {
    SEEDS.forEach { seed ->
      val generated = generator.generate(seed, INSERTS, blank)
      assertEquals(
        emptyList(),
        generated.rejected.map {
          "${it.componentId} at ${it.target} with ${it.selectedNodeId} selected: ${it.outcome}"
        },
        "seed $seed: the palette offered a target the reducer then refused",
      )
      assertTrue(generated.document.nodes.size > blank.nodes.size, "seed $seed generated nothing")
      assertStructurallyValid(generated.document, "seed $seed")
      assertExportAgreesWithTheRecord(generated.document, "seed $seed")
    }
  }

  /** The generator is deterministic: one seed produces one document. */
  @Test
  fun `a seed reproduces its document exactly`() {
    val first = generator.generate(SEEDS.first(), INSERTS, blank)
    val second = generator.generate(SEEDS.first(), INSERTS, blank)

    assertEquals(first.document, second.document)
    assertEquals(first.attempts, second.attempts)
  }

  /**
   * What the palette offers and what the slot rule accepts are the same rule, asked from both ends.
   */
  @Test
  fun `every target the palette offers is one the slot rule accepts`() {
    SEEDS.take(5).forEach { seed ->
      val document = generator.generate(seed, INSERTS, blank).document
      document.nodes.keys.forEach { nodeId ->
        val state =
          reducer.reduce(reducer.initial(document), UiBuilderEditorEvent.SelectNode(nodeId))
        catalog.components.forEach { component ->
          val target = reducer.dropTarget(state, component.componentId) ?: return@forEach
          val parent = document.nodes.getValue(target.nodeId)
          val slot = catalog.componentsById.getValue(parent.componentId).slotsByName[target.slot]
          assertTrue(
            slot != null && slot.accepts(component),
            "seed $seed: with $nodeId selected the palette offered ${component.componentId} the " +
              "slot ${parent.componentId}.${target.slot}, which does not accept it",
          )
        }
      }
    }
  }

  /**
   * Every slot in the catalog can be filled by pressing Add. `findDestination` takes the first
   * accepting slot with room, so a slot behind an unbounded slot accepting the same components
   * would never fill; this keeps such a slot from being added.
   */
  @Test
  fun `every slot in the catalog can be filled from the palette`() {
    val unreachable =
      catalog.components
        .filter { it.slots.isNotEmpty() }
        .flatMap { generator.slotsAddCannotReach(it.componentId, blank) }
        .sorted()

    assertEquals(emptyList(), unreachable)
  }

  /**
   * A slot entry the catalog no longer declares, carrying nothing, is not a finding: inserts write
   * an entry for every declared slot, and the persisted store is validated on load. A child in an
   * undeclared slot is still refused.
   */
  @Test
  fun `a stored button still carrying an empty leading icon is valid and exports`() {
    val button = valid.nodes.values.first { it.componentId == "m3/button" }
    val stored =
      valid.copy(
        nodes =
          valid.nodes +
            (button.id to button.copy(slots = button.slots + ("leadingIcon" to emptyList())))
      )

    assertEquals(emptyList(), validator.validate(stored).issues)
    assertEquals(emptyList(), validateDocumentForExport(stored, catalog))

    val withChild =
      valid.copy(
        nodes =
          valid.nodes +
            ("stray-icon" to UiBuilderNode(id = "stray-icon", componentId = "m3/icon")) +
            (button.id to
              button.copy(slots = button.slots + ("leadingIcon" to listOf("stray-icon"))))
      )
    assertTrue(
      validator.validate(withChild).issues.any {
        it.code == CapabilityIssueCode.UNKNOWN_SLOT && it.nodeId == button.id
      },
      "a child in a withdrawn slot must still be refused",
    )
  }

  /**
   * Every component the record covers, inserted from the palette with its defaults, produces a
   * document that exports to Kotlin. Unlike the goldens (hand-authored operations), an insert
   * writes everything the catalog declares.
   */
  @Test
  fun `every recorded component exports after a palette insert`() {
    val box = blank.nodes.values.first { it.componentId == "layout/box" }.id
    val refused =
      catalog.components
        .map { it.componentId }
        .filter { it in recordedComponentIds }
        .filter { componentId ->
          val state = reducer.reduce(reducer.initial(blank), UiBuilderEditorEvent.SelectNode(box))
          val target = reducer.dropTarget(state, componentId) ?: return@filter false
          val inserted =
            reducer.reduce(state, UiBuilderEditorEvent.InsertComponent(componentId, target))
          inserted.lastOutcome is CommandOutcome.Accepted &&
            reducer.generatedCode(inserted.document) is EditorGeneratedCode.Refused
        }
        .toSet()

    assertEquals(emptySet(), refused)
  }

  /** Structural, catalog and round-trip validity — the tiers that hold for every document. */
  private fun assertStructurallyValid(document: UiBuilderDocument, case: String) {
    assertEquals(
      emptyList(),
      validator.validate(document).issues.map { "${it.code} ${it.nodeId} ${it.message}" },
      "$case: catalog validation",
    )
    assertEquals(
      emptyList(),
      validateDocumentForExport(document, catalog).map { "${it.code} ${it.nodeId} ${it.message}" },
      "$case: structural and catalog validation for export",
    )
    val json = Json { encodeDefaults = true }
    assertEquals(
      document,
      json.decodeFromString(
        UiBuilderDocument.serializer(),
        json.encodeToString(UiBuilderDocument.serializer(), document),
      ),
      "$case: round trip",
    )
  }

  /**
   * A document exports to Kotlin exactly when every component in it has a component record
   * (`M3CatalogComponentRecordTest` in `:server` owns the gaps). An uncovered component must be
   * refused by name.
   */
  private fun assertExportAgreesWithTheRecord(document: UiBuilderDocument, case: String) {
    val used = document.nodes.values.map { it.componentId }.toSet()
    // Semantic loops lower to a Column and a typed repetition; they do not call a catalog symbol.
    // The released floor must still refuse until the additive shared vocabulary is available.
    val repetitions = runCatching {
      Json.decodeFromString<ee.schimke.composeai.discovery.ScreenNode>(
        """{"componentId":"","repetition":{"fields":{},"rows":[]}}"""
      )
    }
      .isSuccess
    // A build without `UiBuilderBuildFeatures.remoteCompose` refuses any document with a
    // repetition, so `layout/for-each` must be expected to refuse there and not be exempted.
    val withheldByBuild = !UiBuilderBuildFeatures.remoteCompose && used.contains("layout/for-each")
    // A variable font text has no catalog record and still exports: the projection records the
    // declaration flexpress generates for it (`VariableFontTextRecord`).
    val expected =
      used -
        VariableFontText.M3_ID -
        recordedComponentIds -
        if (repetitions && !withheldByBuild) setOf("layout/for-each") else emptySet()
    val generated = reducer.generatedCode(document)
    if (expected.isEmpty()) {
      assertEquals(
        emptyList(),
        reducer.problems(document).map { "${it.code} ${it.nodeId} ${it.message}" },
        "$case: every component is recorded, so nothing should be reported",
      )
      assertIs<EditorGeneratedCode.Source>(generated, "$case: every component is recorded")
    } else if (document.nodes.values.any { it.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID }) {
      // `remote-compose/inline` has no component record (its subtree is `@RemoteComposable`), but
      // `InlineRemoteContentExporter` writes that subtree beneath the screen's refusal.
      val source =
        assertIs<EditorGeneratedCode.Source>(generated, "$case: remote content generates")
      assertTrue("@RemoteComposable" in source.kotlin, "$case: ${source.kotlin}")
      assertTrue(
        "The screen around this content is not generated" in source.kotlin,
        "$case: the screen's refusal is kept rather than dropped: ${source.kotlin}",
      )
    } else {
      assertIs<EditorGeneratedCode.Refused>(generated, "$case: expected a refusal for $expected")
    }
  }

  /**
   * The capability ids the shipped component record covers, from BOTH files: the builder's own
   * `layout/`, `shape/` and `asset/` record and the catalog's, merged by
   * `:ui-builder:embedComponentRecord`.
   */
  private val recordedComponentIds: Set<String> by lazy {
    RECORD_FILES.flatMap { name ->
        Json.parseToJsonElement(resource("/$name"))
          .jsonObject
          .getValue("components")
          .jsonArray
          .flatMap { it.jsonObject["componentIds"]?.jsonArray.orEmpty() }
          .map { it.jsonPrimitive.content }
      }
      .toSet()
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    /** The two files `embedComponentRecord` merges; see [recordedComponentIds]. */
    val RECORD_FILES =
      listOf("m3-catalog-components-v1.json", "compose-foundation-components-v1.json")

    /** Fixed seeds so failures can be re-run; twenty fit the `check` lane. */
    val SEEDS = (1L..20L).toList()
    const val INSERTS = 20
  }
}
