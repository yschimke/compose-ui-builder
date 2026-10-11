package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetCodeExporter
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What `remote-m3` gains and loses if `--ui-builder-published-catalogs` names it, using a pair
 * captured verbatim from `:remote-catalog:composePreviewDiscover`.
 *
 * - It offers Remote Compose components the synthesised shelf never had; most now export, and
 *   those that cannot are pinned below with the reason.
 * - The two widget containers are restored as builtins (a Glance Wear host frame has no record),
 *   which matters because `RecordFreeExport` routes on the root component id.
 * - `m3/text` is not lost: it is `remote-m3/remote-text`. `remote-m3/lottie` is server-built from
 *   `asset/image`, so the catalog has nothing to publish.
 *
 * The donor only unions `layout/`, `shape/`, `asset/` and `remote-compose/`; anything else the
 * published file omits is gone.
 */
class PublishedRemoteM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  /** See [PUBLISHED_CATALOG_FIXTURES]. */
  private fun fixture(name: String) = publishedCatalogFixture(name)

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("remote-m3-capabilities-v1.json"))

  private val result: PublishedUiBuilderCatalog.Result.Composed by lazy {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("remote-m3-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(fixture("remote-m3-published-v1.json"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the generated pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    result as PublishedUiBuilderCatalog.Result.Composed
  }

  private val composed: CatalogCapabilityV1
    get() = result.catalog

  /**
   * The record behind each published component, under its builder id — the composition's own
   * join, the same map `ServeRunner` hands the export executor.
   */
  private val composedRecords: Map<String, ComponentRecord>
    get() = result.records

  /**
   * The ones the published catalog cannot offer, and why. Asserted absent rather than skipped, so
   * one that starts composing fails this test.
   */
  private val knownAbsent =
    mapOf(
      "remote-m3/lottie" to
        "the catalog draws no Lottie sticker at all — `grep -r Lottie remote-catalog/src/main` " +
          "finds nothing — so there is no component here to publish. It exists on the " +
          "synthesised shelf because the server builds one from `asset/image` and the emitter " +
          "can write Horologist's `LottieAnimation`. Publishing it means the catalog drawing " +
          "one, on a shelf it would have to declare",
      "m3/text" to
        "NOT a loss: `m3/text` IS `RemoteText` in " +
          "remote-material3, and this catalog publishes it under its own id, " +
          "`remote-m3/remote-text`. What the swap changes is the id a design stores, not whether " +
          "the component is there — and the emitter has written `m3/text` as `RemoteText` all " +
          "along, which is the same identity spelled the m3 way",
    )

  /** The four namespaces `ProductionUiBuilderRuntime` donates back to a published catalog. */
  private val donorNamespaces = listOf("layout/", "shape/", "asset/", "remote-compose/")

  /**
   * Everything the synthesised shelf offers that the donor will not put back; the published file
   * must carry these.
   */
  private fun frozenOwned() =
    frozen.components.filterNot { c -> donorNamespaces.any { c.componentId.startsWith(it) } }

  /** Every component the pair composes, by name; a count floor would miss components vanishing. */
  @Test
  fun `the generated pair composes exactly the components it should`() {
    assertEquals(
      listOf(
        "remote-m3/remote-app-card",
        "remote-m3/remote-button",
        "remote-m3/remote-button-group",
        "remote-m3/remote-card",
        "remote-m3/remote-checkbox-button",
        "remote-m3/remote-circular-progress-indicator",
        "remote-m3/remote-compact-button",
        "remote-m3/remote-curved-progress-indicator",
        "remote-m3/remote-edge-button",
        "remote-m3/remote-horizontal-page-indicator",
        "remote-m3/remote-icon",
        "remote-m3/remote-icon-button",
        "remote-m3/remote-linear-progress-indicator",
        "remote-m3/remote-outlined-card",
        "remote-m3/remote-radio-button",
        "remote-m3/remote-slider",
        "remote-m3/remote-split-checkbox-button",
        "remote-m3/remote-split-radio-button",
        "remote-m3/remote-split-switch-button",
        "remote-m3/remote-stepper",
        "remote-m3/remote-switch-button",
        "remote-m3/remote-text",
        "remote-m3/remote-text-button",
        "remote-m3/remote-title-card",
        "remote-m3/remote-vertical-page-indicator",
        "remote-m3/theme-specimen",
        // Not records: host-frame builtins declared by the policy. `widget-container-adaptive` is
        // the experimental third, resolved to Small or Large before rendering.
        "remote-m3/widget-container-adaptive",
        "remote-m3/widget-container-large",
        "remote-m3/widget-container-small",
      ),
      composed.components.map { it.componentId }.filter { it.startsWith("remote-m3/") }.sorted(),
      "the composed remote-m3 shelf has changed",
    )
  }

  /**
   * `remote-sticker` (the preview frame, not a placeable component) is excluded everywhere: not
   * served, not exported, and not on the menu. The menu assertion guards a fix that lives in
   * another repository.
   */
  @Test
  fun `an excluded component is not served`() {
    val semantics =
      json
        .parseToJsonElement(fixture("remote-m3-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject
    val excluded =
      semantics["components"]!!
        .jsonObject
        .filterValues { component ->
          component.jsonObject["excluded"].let { it != null && it !is JsonNull }
        }
        .keys
        .sorted()
    // The catalog uses the released `CapturingWearWidgetPreview`, so its wrapper is no longer in
    // the record.
    assertEquals(
      listOf("remote-m3/remote-sticker"),
      excluded,
      "the set of components the catalog excludes has changed",
    )

    val offered = composed.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      excluded.filter { it in offered },
      "an excluded component is being served — the published reason says the catalog refuses it",
    )

    val menu = semantics["componentMenu"]!!.jsonObject["components"]!!.jsonObject
    assertEquals(
      emptyList(),
      excluded.filter { it in menu },
      "an excluded component is back on the menu — a palette entry for something the consumer " +
        "refuses to serve is an item that disappears on insert (compose-ai-tools#5378)",
    )
  }

  /**
   * The restored containers match the frozen ones field by field (name, traits, slots including
   * `max: 1`, properties) — not just ids. An empty list here means no divergence.
   */
  @Test
  fun `a restored widget container matches the frozen one field by field`() {
    val byId = composed.components.associateBy { it.componentId }
    val frozenById = frozen.components.associateBy { it.componentId }
    val differences = mutableListOf<String>()
    for (id in listOf("remote-m3/widget-container-small", "remote-m3/widget-container-large")) {
      val want = frozenById.getValue(id)
      val got = byId[id] ?: error("$id did not compose")
      fun check(field: String, a: Any?, b: Any?) {
        if (a != b) differences += "$id.$field: frozen=$a composed=$b"
      }
      check("displayName", want.displayName, got.displayName)
      check("role", want.role, got.role)
      check("traits", want.traits.sorted(), got.traits.sorted())
      check("modifierCapabilities", want.modifierCapabilities, got.modifierCapabilities)
      check("properties", want.properties.map { it.name }, got.properties.map { it.name })
      for (property in want.properties) {
        val mine = got.properties.singleOrNull { it.name == property.name } ?: continue
        check("properties[${property.name}].jsonType", property.jsonType, mine.jsonType)
        check("properties[${property.name}].required", property.required, mine.required)
      }
      check("slots", want.slots.map { it.name }.sorted(), got.slots.map { it.name }.sorted())
      for (slot in want.slots) {
        val mine = got.slots.singleOrNull { it.name == slot.name } ?: continue
        check("slots[${slot.name}].acceptedRoles", slot.acceptedRoles, mine.acceptedRoles)
        check("slots[${slot.name}].acceptedTraits", slot.acceptedTraits, mine.acceptedTraits)
        check("slots[${slot.name}].cardinality.min", slot.cardinality.min, mine.cardinality.min)
        check("slots[${slot.name}].cardinality.max", slot.cardinality.max, mine.cardinality.max)
      }
    }
    assertEquals(
      REVIEWED_CONTAINER_DIFFERENCES.sorted(),
      differences.sorted(),
      "a restored container differs from the frozen one in a way nobody has reviewed",
    )
  }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of components the published remote-m3 catalog cannot offer has changed",
    )
  }

  /**
   * The components reach the shelf at all, so a discovery regression shows up here. Exportability
   * is measured separately below.
   */
  @Test
  fun `the Remote Compose components the catalog draws are all offered`() {
    val offered = composed.components.map { it.componentId }.toSet()
    val expected =
      listOf(
        "remote-m3/remote-button",
        "remote-m3/remote-card",
        "remote-m3/remote-text",
        "remote-m3/remote-icon",
        "remote-m3/remote-icon-button",
        "remote-m3/remote-checkbox-button",
        "remote-m3/remote-radio-button",
        "remote-m3/remote-switch-button",
        "remote-m3/remote-slider",
        "remote-m3/remote-stepper",
        "remote-m3/remote-edge-button",
        "remote-m3/remote-title-card",
        "remote-m3/remote-app-card",
      )
    assertEquals(
      emptyList(),
      expected.filterNot { it in offered },
      "components remote-catalog draws are missing from the published shelf",
    )
  }

  /**
   * Which published components `RemoteContentEmitter` has hand-written cases for, versus the record
   * fallback. Kept separate from the export test because the two mechanisms fail differently, and
   * a new case should be deliberate.
   *
   * Asserted as the exact current set. The exclusion of `RemoteMaterial3` components is taken only
   * when the vocabulary names all of them (the pinned 3.44.0 has the record but not the
   * vocabulary); once the pin moves past 3.44.0 this can become the literal three.
   */
  @Test
  fun `the emitter has a hand-written case for one of the components the catalog adds`() {
    val offered = composed.components.map { it.componentId }
    val inexportable =
      offered
        .filterNot { it in REMOTE_CONTENT_COMPONENT_IDS }
        .filterNot { it.startsWith("remote-m3/widget-container-") }
        .sorted()
    val recordWritten = RemoteMaterial3.components.map { it.componentId }.toSet()
    val recordWrittenInVocabulary = recordWritten.filter { it in REMOTE_CONTENT_COMPONENT_IDS }
    assertTrue(
      recordWrittenInVocabulary.isEmpty() || recordWrittenInVocabulary.toSet() == recordWritten,
      "the vocabulary names some of the record-written Remote Material 3 components but not " +
        "others: ${recordWritten - recordWrittenInVocabulary.toSet()}",
    )
    assertEquals(
      offered
        .filter { it.startsWith("remote-m3/") }
        .filterNot { it.startsWith("remote-m3/widget-container-") }
        .filterNot { it == REMOTE_TEXT_COMPONENT_ID }
        .filterNot { it in recordWrittenInVocabulary }
        .sorted(),
      inexportable,
      "the set of offered-but-unexportable components has changed — if the emitter grew a case, " +
        "shorten this; if the catalog grew a component, it needs one",
    )
    assertTrue(
      REMOTE_TEXT_COMPONENT_ID in offered &&
        REMOTE_TEXT_COMPONENT_ID in REMOTE_CONTENT_COMPONENT_IDS,
      "the one case that was shortened out has to be a component this catalog actually offers",
    )
  }

  /**
   * Every modifier the published catalog advertises is one this lane can write: a control you can
   * apply but not export is worse than a missing one. Asserted empty; any entry is a regression.
   */
  @Test
  fun `every modifier the published catalog advertises can be written`() {
    val offending =
      composed.components
        .filter { it.componentId.startsWith("remote-m3/") }
        .flatMap { component -> component.modifierCapabilities.map { component.componentId to it } }
        .filterNot { (_, modifier) -> modifier in REMOTE_CONTENT_MODIFIERS }
        .distinct()
        .sortedBy { it.second }
    assertEquals(
      emptyList(),
      offending,
      "the published shelf advertises a modifier `RemoteContentEmitter` cannot write — a control " +
        "an author can apply and then cannot export",
    )
  }

  /**
   * What the export blocker costs, from the record's `code.refusedReason`: a histogram of required
   * parameter types with no writable placeholder value, rather than a count of components.
   *
   * Caveats: a placeholder refusal is not an export refusal (an export has the design's real
   * value), except `Action`, which designs do not carry. Compilation of generated source is checked
   * in wear-m3-catalog, not here.
   */
  @Test
  fun `the export blocker is six value types, not twenty-five components`() {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("remote-m3-record-v1.json"))
    val refusals =
      record.components.mapNotNull { it.code?.refusedReason }.groupingBy { it }.eachCount()
    val byRequiredType =
      refusals.entries
        .groupingBy { (reason, _) ->
          reason.substringAfterLast(": ").removeSuffix("`").ifBlank { reason }
        }
        .fold(0) { total, (_, count) -> total + count }

    assertEquals(
      25,
      refusals.values.sum(),
      "the number of components with no call site has changed; 27 components, 2 of which write one",
    )
    assertEquals(
      mapOf(
        "Action" to 9,
        "RemoteBoolean" to 6,
        "RemoteFloat" to 5,
        "RemotePageIndicatorState" to 2,
        // `RemoteImageVector`: what the current record names RemoteIcon's parameter type.
        "RemoteImageVector" to 1,
        "RemoteString" to 1,
        // Not a type at all — one callable this catalog cannot call from a generated file.
        "not public or internal, so a generated file cannot call it" to 1,
      ),
      byRequiredType,
      "the set of types blocking a call site has changed",
    )
  }

  /**
   * How much of the published shelf can be exported, component by component: for each, a widget
   * whose body is that component with every expressible required parameter authored. What still
   * refuses is pinned with the emitter's reason.
   */
  @Test
  fun `what the published shelf can export is the reviewed set`() {
    val byId = composedRecords.filterKeys {
      it.startsWith("remote-m3/") && !it.startsWith("remote-m3/widget-container-")
    }

    val refused = mutableMapOf<String, String>()
    for ((id, component) in byId) {
      val result =
        WearWidgetCodeExporter.export(
          widgetAround(id, component),
          components = mapOf(id to component),
        )
      if (result is WearWidgetCodeExporter.Result.Refused) {
        refused[id] = result.reasons.first().substringAfter("requires ", result.reasons.first())
      }
    }

    // The denominator, pinned, so a join that silently dropped components does not look like an
    // improvement.
    assertEquals(
      26,
      byId.size,
      "the number of published remote-m3 components measured for export has changed",
    )
    assertEquals(
      EXPORT_REFUSALS,
      refused.toSortedMap().toMap(),
      "the set of published components the widget exporter cannot write has changed",
    )
  }

  /** A widget whose whole body is [id], with every required parameter the design can express. */
  private fun widgetAround(id: String, component: ComponentRecord): UiBuilderDocument {
    val properties = buildJsonObject {
      for (parameter in component.parameters) {
        if (parameter.hasDefault || parameter.composableSlot) continue
        val authored =
          when (parameter.typeFqn) {
            "androidx.compose.remote.creation.compose.state.RemoteString",
            "kotlin.String" -> designValue("string", JsonPrimitive("value"))
            "androidx.compose.remote.creation.compose.state.RemoteFloat",
            "kotlin.Float" -> designValue("number", JsonPrimitive(0.5))
            "androidx.compose.remote.creation.compose.state.RemoteBoolean",
            "kotlin.Boolean" -> designValue("boolean", JsonPrimitive(true))
            "androidx.compose.remote.creation.compose.state.RemoteColor" ->
              designValue("string", JsonPrimitive("#FF6750A4"))
            "kotlin.Int" -> designValue("number", JsonPrimitive(1))
            else -> null
          }
        if (authored != null) put(parameter.name, authored)
      }
    }
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "export-parity",
      title = "Export parity",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("host"),
      nodes =
        mapOf(
          "host" to
            UiBuilderNode(
              id = "host",
              componentId = "remote-m3/widget-container-small",
              slots = mapOf("content" to listOf("subject")),
            ),
          "subject" to UiBuilderNode(id = "subject", componentId = id, properties = properties),
        ),
    )
  }

  private fun designValue(type: String, value: JsonPrimitive) = buildJsonObject {
    put("type", JsonPrimitive(type))
    put("value", value)
  }

  /**
   * Every shelf the components use must be in the menu order, or the editor appends and sorts it
   * itself. Both directions are asserted; an ordered group with no component is covered below.
   */
  @Test
  fun `the menu order covers the groups the shelf actually uses`() {
    val menu =
      json
        .parseToJsonElement(fixture("remote-m3-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject
    val order =
      menu["groupOrder"]!!
        .let { it as kotlinx.serialization.json.JsonArray }
        .map { it.jsonPrimitive.content }
    val used =
      menu["components"]!!
        .jsonObject
        .values
        .mapNotNull { it.jsonObject["group"]?.jsonPrimitive?.content }
        .distinct()
        .sorted()

    assertEquals(
      emptyList(),
      used.filterNot { it in order },
      "groups the shelf uses that the order does not name — the editor sorts these alphabetically " +
        "after every ordered group, so the catalog's stated order is not the one a person sees",
    )
    // Ordered groups with no component: the cost of one id per symbol (e.g. `RemoteText` is one
    // record shelved with its sorted-first sticker). The policy's `group` is how a catalog places
    // such components. Asserted as the exact set; the remaining entries hold stickers that publish
    // no component of their own.
    assertEquals(
      listOf(
        "Confetti",
        "Scaffold templates",
        "Shaders",
        "Shapes",
        "Typeface",
        "Wear M3",
        "Widget Container",
      ),
      order.filterNot { it in used }.sorted(),
      "groups the order names that no component is on",
    )
  }

  /**
   * The builder's own vocabulary survives the swap; the frozen entries outside donor namespaces are
   * asserted in the gap test above.
   */
  @Test
  fun `a published remote-m3 catalog is still served the builder's own vocabulary`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("remote-m3"),
          published = mapOf("remote-m3" to composed),
        )
        .listCatalogs()
        .single()
    val builderOwned =
      frozen.components
        .map { it.componentId }
        .filter { id -> donorNamespaces.any { id.startsWith(it) } }
        .sorted()
    val servedIds = served.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      builderOwned.filterNot { it in servedIds },
      "a published catalog lost builder components the synthesised one offers",
    )
  }

  /**
   * The platform traits every record component gets on a Remote Compose catalog, which the
   * synthesised shelf always gave and a published catalog stating nothing used to lose.
   */
  @Test
  fun `a remote record component carries the emitter's traits, slots, bindings and modifiers`() {
    val button = composed.components.single { it.componentId == "remote-m3/remote-button" }
    assertTrue(
      button.traits.containsAll(listOf("RemoteContent", "RemoteAuthorable")),
      "${button.traits}",
    )
    val content = button.slots.single { it.name == "content" }
    assertEquals(listOf("RemoteAuthorable"), content.acceptedTraits)
    assertEquals(setOf("Scaffold", "Container", "Leaf"), content.acceptedRoles.toSet())
    // State-bindable: a literal, or a reference to the document's Boolean state.
    assertEquals(
      JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
      button.properties.single { it.name == "enabled" }.jsonType,
    )
    // Leaf or container, the default is what the emitter writes.
    val leaf = composed.components.single { it.componentId == "remote-m3/remote-icon" }
    for (component in listOf(button, leaf)) {
      assertEquals(
        REMOTE_CONTENT_MODIFIERS,
        component.modifierCapabilities.toSet(),
        component.componentId,
      )
    }
  }

  private companion object {
    /**
     * Reviewed container differences, one line per container.
     *
     * The frozen shelf narrows the content slot to `RemoteAuthorable`, but traits reach a published
     * catalog only from the record, which never carries it, so `AnyContent` is correct until it
     * can. Delete a line when the published side carries the trait.
     */
    val REVIEWED_CONTAINER_DIFFERENCES =
      listOf("small", "large").map {
        "remote-m3/widget-container-$it.slots[content].acceptedTraits: " +
          "frozen=[RemoteAuthorable] composed=[AnyContent]"
      }

    /**
     * The published components the widget exporter cannot write, and why.
     * `remote-m3/theme-specimen` is not public, so no generated file can call it.
     */
    val EXPORT_REFUSALS =
      mapOf(
        "remote-m3/theme-specimen" to
          "`remote-m3/theme-specimen` is not public or internal, so a generated file cannot " +
            "call it"
      )
  }
}
