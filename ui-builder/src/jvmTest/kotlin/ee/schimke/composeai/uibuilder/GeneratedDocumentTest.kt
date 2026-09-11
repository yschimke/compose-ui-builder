package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.accepts
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
 * The two halves of that question are one suite. [UiBuilderTreeGenerator] walks the catalog as a
 * grammar and builds documents by the same select-and-Add a person presses; the assertions here are
 * the oracles a document has to satisfy at each tier it can be wrong at — structural (one root, no
 * dangling ids, no node in two slots, no cycles, cardinality), catalog (the slot accepts the child,
 * required properties, declared enum values, allowed modifiers), export (the Compose exporter
 * writes it), and round trip (it survives being written down and read back).
 *
 * Seeds are fixed so a failure reproduces from the seed alone. This is not a proof over every tree
 * the catalog admits — that set is infinite — but the first test below *is* exhaustive for one
 * insert from a real document, and it is the one that answers the question in the title.
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
   * Every offer the palette makes is one the reducer takes, and one that leaves a valid document.
   *
   * Exhaustive rather than random: every node of the blank template, against every component in the
   * catalog. An offer the reducer then refuses would be a component a person can select in the
   * panel, press Add on, and watch nothing happen — and it fails here naming the pair.
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

  /**
   * The generator is not testing itself: one seed produces one document, so a failure above
   * reproduces from the seed rather than being a shape that has already gone.
   */
  @Test
  fun `a seed reproduces its document exactly`() {
    val first = generator.generate(SEEDS.first(), INSERTS, blank)
    val second = generator.generate(SEEDS.first(), INSERTS, blank)

    assertEquals(first.document, second.document)
    assertEquals(first.attempts, second.attempts)
  }

  /**
   * What the palette offers and what the slot rule accepts are the same rule, asked from both ends.
   *
   * A target the panel names must be one the rule accepts, or Add puts a component somewhere the
   * validator will refuse. The old either-role-or-trait rule failed exactly this: with a blank
   * scaffold selected, a lazy grid was offered the top bar.
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
   * Every slot in the catalog can be filled by pressing Add.
   *
   * `findDestination` takes the **first** slot that accepts the component and has room, so a slot
   * sitting behind an unbounded slot that accepts the same thing would never come up — the
   * unbounded one never fills. One slot was in exactly that position: `m3/button.leadingIcon` sat
   * behind the button's unbounded `content`, which accepts an icon too, so the icon always landed
   * in `content` and the slot stayed empty however long somebody pressed Add.
   *
   * It was also the one slot the Compose export could not write, and for the same underlying
   * reason: Material's `Button` takes one content lambda and has no leading-icon parameter, so an
   * icon goes *inside* the content beside the label. The slot is gone
   * (yschimke/compose-preview-server#430) and this is what keeps another from appearing — a new
   * slot ordered behind an unbounded one that accepts the same components fails here.
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
   * A slot entry the catalog no longer declares, carrying nothing, is not a finding.
   *
   * The editor writes an entry for every slot the catalog declares at the moment of the insert, so
   * those keys outlive the declaration: every button inserted before `m3/button.leadingIcon` was
   * withdrawn still carries an empty one, in every design anybody saved. Refusing them would mean a
   * catalog could never drop a slot without invalidating documents that never used it — and the
   * persisted store is validated on load, so "invalid" there means the service does not start.
   *
   * A child in an undeclared slot is still refused, because that child would be silently dropped.
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
   * A component the record covers, inserted from the palette with the defaults the palette gives
   * it, produces a document that exports to Kotlin. Every one of them, with no exceptions left.
   *
   * There were two when this was written, and both were bugs in what the insert writes rather than
   * in the exporter: `m3/button` arrived carrying an empty `leadingIcon` the record had no
   * parameter for, and `m3/progress-indicator` arrived determinate, which needs a `progress: () ->
   * Float` no value in the document vocabulary can be. The slot was withdrawn in #430 and the
   * determinate export landed in #435, and this test is how the second one was noticed: it had been
   * pinned as known, so fixing it turned this red and asked for the pin to go.
   *
   * This is the test the checked-in goldens cannot be: they replay hand-authored operation lists,
   * which write only the slots and properties somebody meant to write, where an insert writes
   * everything the catalog declares.
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
   * A document exports to Kotlin exactly when every component in it has a component record.
   *
   * Nineteen of the catalog's thirty-nine do not, each for a stated reason — a `SnackbarHostState`
   * no `ScreenValue` expresses, a scope rather than a plain composable slot — and
   * `M3CatalogComponentRecordTest` in `:server` owns that table. What matters here is that the two
   * agree: a document of covered components must export, and one carrying an uncovered component
   * must refuse *naming it*, rather than refusing for some unrelated reason the record gap was
   * hiding.
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
    val expected =
      used - recordedComponentIds - if (repetitions) setOf("layout/for-each") else emptySet()
    val generated = reducer.generatedCode(document)
    if (expected.isEmpty()) {
      assertEquals(
        emptyList(),
        reducer.problems(document).map { "${it.code} ${it.nodeId} ${it.message}" },
        "$case: every component is recorded, so nothing should be reported",
      )
      assertIs<EditorGeneratedCode.Source>(generated, "$case: every component is recorded")
    } else if (document.nodes.values.any { it.componentId == REMOTE_COMPOSE_INLINE_COMPONENT_ID }) {
      // The one case where an unrecorded component still produces Kotlin, and it is a second
      // generator rather than a hole in this rule. `remote-compose/inline` has no component record
      // because no Compose call site could have one — its subtree is `@RemoteComposable` — so
      // `InlineRemoteContentExporter` writes that subtree and the pane shows it beneath the
      // screen's own refusal, kept as a header comment. A design holding remote content answered
      // with a bare "cannot export" would be the least useful true thing that could be said.
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

  /** The capability ids the shipped component record covers. */
  private val recordedComponentIds: Set<String> by lazy {
    Json.parseToJsonElement(resource("/m3-catalog-components-v1.json"))
      .jsonObject
      .getValue("components")
      .jsonArray
      .flatMap { it.jsonObject["componentIds"]?.jsonArray.orEmpty() }
      .map { it.jsonPrimitive.content }
      .toSet()
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    /**
     * Fixed rather than drawn from the clock: a generative test that cannot be re-run on the seed
     * that failed is a bug report nobody can act on. Twenty seeds is what fits in the `check` lane;
     * a wider sweep belongs in a nightly rather than here.
     */
    val SEEDS = (1L..20L).toList()
    const val INSERTS = 20
  }
}
