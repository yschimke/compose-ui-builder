package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The other direction from [GeneratedDocumentTest]: every tree the rules forbid is refused, and
 * refused for the stated reason.
 *
 * Each case starts from a document the generator built — so the only thing wrong with it is the one
 * thing the mutation did — breaks exactly one rule, and asserts the code that names it. One tier at
 * a time is what makes a failure here readable: a document broken three ways would be refused
 * whatever the validator had actually noticed, and would pass a test that asserted only "refused".
 *
 * The mutations write the document directly, which is deliberate: this asks what the *validator*
 * does with a bad document, however it arrived — a hand-edited file, an agent's `create_design`, a
 * future operation with a bug in it. What the editor's own doors do with the same intent is the
 * second half, at the bottom.
 */
class MutatedDocumentTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val validator = CapabilityValidator(catalog)
  private val generator = UiBuilderTreeGenerator(catalog, reducer)
  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val blank = blankUiBuilderDocument("mutated", jetcaster.catalogPin, jetcaster.environment)

  /** A generated document with a scaffold, a box and a few components under them. */
  private val valid = generator.generate(seed = 7, inserts = 12, start = blank).document

  @Test
  fun `the document the mutations start from is itself valid`() {
    assertEquals(emptyList(), validator.validate(valid).issues)
    assertEquals(emptyList(), validateDocumentForExport(valid, catalog))
  }

  @Test
  fun `a child in a slot that does not accept it is refused`() {
    // The `/ui-builder/m3-catalog/a` shape: a lazy grid in a scaffold's top bar. The grid is a
    // `Container`, which is what the old rule took as enough; the missing `TopBar` is the finding.
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    val grid = UiBuilderNode(id = "grid", componentId = "layout/lazy-grid")
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (grid.id to grid) +
            (scaffold.id to scaffold.copy(slots = scaffold.slots + ("topBar" to listOf(grid.id))))
      )

    assertRefused(mutated, CapabilityIssueCode.INCOMPATIBLE_SLOT_CHILD, scaffold.id)
  }

  @Test
  fun `a required slot emptied is refused`() {
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to emptyList())))
      )

    // The children it lost are now unreachable, which is a second, true finding — the assertion is
    // that the cardinality is among what is reported, not that it is alone.
    assertRefused(mutated, CapabilityIssueCode.SLOT_CARDINALITY, scaffold.id)
  }

  @Test
  fun `a slot filled past its maximum is refused`() {
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    val extra = UiBuilderNode(id = "extra-box", componentId = "layout/box")
    val content = scaffold.slots.getValue("content")
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (extra.id to extra) +
            (scaffold.id to
              scaffold.copy(slots = scaffold.slots + ("content" to content + extra.id)))
      )

    assertRefused(mutated, CapabilityIssueCode.SLOT_CARDINALITY, scaffold.id)
  }

  @Test
  fun `a slot naming a child that does not exist is refused`() {
    val box = valid.nodes.values.first { it.componentId == "layout/box" }
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (box.id to
              box.copy(
                slots = box.slots + ("children" to box.slots["children"].orEmpty() + "gone")
              ))
      )

    assertRefused(mutated, CapabilityIssueCode.UNKNOWN_CHILD, box.id)
  }

  /**
   * A child in a slot the component does not declare is refused; an entry with nothing in it is
   * not.
   *
   * The empty half is deliberate and is what lets the catalog withdraw a slot without invalidating
   * every stored document that never used it — the editor writes a key for every slot declared at
   * the moment of the insert, so those keys outlive the declaration. `m3/button.leadingIcon` is the
   * one that has been withdrawn, and `GeneratedDocumentTest` holds that case end to end.
   */
  @Test
  fun `a child in a slot the component does not declare is refused`() {
    val box = valid.nodes.values.first { it.componentId == "layout/box" }
    val stray = UiBuilderNode(id = "stray", componentId = "m3/icon")
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (stray.id to stray) +
            (box.id to box.copy(slots = box.slots + ("nope" to listOf(stray.id))))
      )

    assertRefused(mutated, CapabilityIssueCode.UNKNOWN_SLOT, box.id)

    val empty =
      valid.copy(
        nodes = valid.nodes + (box.id to box.copy(slots = box.slots + ("nope" to emptyList())))
      )
    assertEquals(emptyList(), validator.validate(empty).issues)
  }

  @Test
  fun `a component the catalog does not declare is refused`() {
    val box = valid.nodes.values.first { it.componentId == "layout/box" }
    val mutated =
      valid.copy(nodes = valid.nodes + (box.id to box.copy(componentId = "m3/not-a-component")))

    assertRefused(mutated, CapabilityIssueCode.UNKNOWN_COMPONENT, box.id)
  }

  @Test
  fun `a missing required property is refused`() {
    val text = valid.nodes.values.first { it.componentId == "m3/text" }
    val mutated =
      valid.copy(
        nodes =
          valid.nodes + (text.id to text.copy(properties = JsonObject(text.properties - "text")))
      )

    assertRefused(mutated, CapabilityIssueCode.MISSING_REQUIRED_PROPERTY, text.id)
  }

  @Test
  fun `an enum value the catalog does not allow is refused`() {
    val text = valid.nodes.values.first { it.componentId == "m3/text" }
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (text.id to
              text.copy(
                properties =
                  JsonObject(
                    text.properties +
                      ("style" to
                        buildJsonObject {
                          put("type", "enum")
                          put("value", "notATypeScaleToken")
                        })
                  )
              ))
      )

    assertRefused(mutated, CapabilityIssueCode.INVALID_PROPERTY_VALUE, text.id)
  }

  @Test
  fun `a modifier the component does not allow is refused`() {
    val text = valid.nodes.values.first { it.componentId == "m3/text" }
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (text.id to
              text.copy(
                modifiers =
                  JsonArray(listOf(buildJsonObject { put("type", "notAModifierAnyoneDeclares") }))
              ))
      )

    assertRefused(mutated, CapabilityIssueCode.UNKNOWN_MODIFIER, text.id)
  }

  @Test
  fun `a node in two slots at once is refused`() {
    val box = valid.nodes.values.first { it.componentId == "layout/box" }
    val stolen = box.slots.getValue("children").firstOrNull() ?: return
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    // The scaffold's top bar is not where it belongs either, but the point is the second parent:
    // any slot that accepts it would do, and `layout/box` is what a top bar takes.
    val second = UiBuilderNode(id = "second-parent", componentId = "layout/box")
    val mutated =
      valid.copy(
        nodes =
          valid.nodes +
            (second.id to second.copy(slots = mapOf("children" to listOf(stolen)))) +
            (scaffold.id to scaffold.copy(slots = scaffold.slots + ("topBar" to listOf(second.id))))
      )

    assertGraphRefused(mutated, "DUPLICATE_NODE_REFERENCE")
  }

  @Test
  fun `a slot that leads back to an ancestor is refused`() {
    val box = valid.nodes.values.first { it.componentId == "layout/box" }
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    val mutated =
      valid.copy(
        nodes = valid.nodes + (box.id to box.copy(slots = mapOf("children" to listOf(scaffold.id))))
      )

    assertGraphRefused(mutated, "GRAPH_CYCLE")
  }

  /**
   * Zero roots and two roots, which only the export gate refuses — no topology check on either the
   * client or the server bounds the count, so both are states a stored design can be in. That is
   * yschimke/compose-preview-server#429, and this is the assertion that will still hold once it is
   * fixed: whatever else starts refusing them, the export always did.
   */
  @Test
  fun `a document without exactly one root is refused`() {
    assertGraphRefused(valid.copy(roots = emptyList()), "ROOT_CARDINALITY")

    val second = UiBuilderNode(id = "second-root", componentId = "layout/box")
    assertGraphRefused(
      valid.copy(roots = valid.roots + second.id, nodes = valid.nodes + (second.id to second)),
      "ROOT_CARDINALITY",
    )
  }

  @Test
  fun `a node nothing points at is refused`() {
    val orphan = UiBuilderNode(id = "orphan", componentId = "layout/box")

    assertGraphRefused(valid.copy(nodes = valid.nodes + (orphan.id to orphan)), "UNREACHABLE_NODE")
  }

  /**
   * The same intent at the door a person uses, rather than at the validator.
   *
   * Being refused is not enough: a refused operation has to leave the document exactly as it was,
   * or the editor has committed half of something nobody asked for.
   */
  @Test
  fun `the editor refuses the same intents and changes nothing`() {
    val scaffold = valid.nodes.values.first { it.componentId == "layout/scaffold" }
    val state = reducer.initial(valid, scaffold.id)

    // A grid into the top bar, named explicitly rather than resolved — the palette would not offer
    // it, and the reducer refuses it when asked anyway.
    val intoTopBar =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.InsertComponent("layout/lazy-grid", ParentSlot(scaffold.id, "topBar")),
      )
    assertIs<CommandOutcome.Rejected>(intoTopBar.lastOutcome)
    assertEquals(valid.nodes, intoTopBar.document.nodes)

    // Emptying the scaffold's required content slot, which the delete guard refuses outright.
    val contentId = scaffold.slots.getValue("content").single()
    val selected = reducer.initial(valid, contentId)
    assertTrue(
      !reducer.canDeleteSelected(selected),
      "deleting the only child of a required slot must not be offered",
    )
    val deleted = reducer.reduce(selected, UiBuilderEditorEvent.DeleteSelected)
    assertEquals(valid.nodes, deleted.document.nodes)
  }

  private fun assertRefused(
    document: UiBuilderDocument,
    code: CapabilityIssueCode,
    nodeId: String,
  ) {
    val issues = validator.validate(document).issues
    assertTrue(
      issues.any { it.code == code && it.nodeId == nodeId },
      "expected $code on $nodeId, got ${issues.map { "${it.code}@${it.nodeId}: ${it.message}" }}",
    )
  }

  private fun assertGraphRefused(document: UiBuilderDocument, code: String) {
    val issues = validateDocumentForExport(document, catalog)
    assertTrue(
      issues.any { it.code == code },
      "expected $code, got ${issues.map { "${it.code}: ${it.message}" }}",
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
