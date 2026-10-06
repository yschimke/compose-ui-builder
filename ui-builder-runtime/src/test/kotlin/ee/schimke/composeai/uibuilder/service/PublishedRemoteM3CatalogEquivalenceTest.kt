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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What `remote-m3` gains and what it loses if `--ui-builder-published-catalogs` names it.
 *
 * The m3 sibling of this test composes a fixture written by hand, which proved the composer and
 * proved nothing about the catalog — see its KDoc. **This pair is generated**, captured verbatim
 * from `:remote-catalog:composePreviewDiscover`, so the question here is the real one.
 *
 * The answer is not symmetric, and that is the point of writing it down:
 * - it **offers** twenty-five Remote Compose components the synthesised shelf has never had, which
 *   until recently the record could not name at all (all 49 catalog ids collapsed onto the
 *   project's own `RemoteSticker` wrapper, two records for the whole module). When this was written
 *   not one of them could be EXPORTED, so calling them a gain would have been wrong; twenty-two of
 *   twenty-six now can, and the four that cannot are named below with the reason;
 * - it **loses** two — three until the frozen side dropped `m3/surface` — and it lost more until
 *   the two that mattered most came back.
 *
 * The loss is a blocker, and nothing else states it. `ProductionUiBuilderRuntime`'s donor unions in
 * `layout/`, `shape/`, `asset/` and `remote-compose/` and nothing else — deliberately, because
 * those are the builder's own vocabulary rather than any catalog's. Anything else the synthesised
 * shelf offers and the published file omits is simply gone.
 *
 * **The two widget containers came back as builtins**, which is what a builtin is for: the Glance
 * Wear container is a HOST frame rather than a `remote-material3` component, so nothing calls it
 * and no record can carry it, and `UiBuilderBuiltin`'s own KDoc says a policy file may declare
 * exactly that. They mattered more than two components' worth — this catalog's policy says *"a
 * Remote design starts from a container: everything else goes inside one"* and `RecordFreeExport`
 * routes on the ROOT component id, so without them a published catalog is one whose designs cannot
 * be rooted.
 *
 * That path did not work before this PR: the composer read a builtin's properties under a key the
 * policy schema forbids, dropped its slot policy, and hardcoded its traits to empty. Three defects
 * on a seam no catalog had ever crossed.
 *
 * **The other two are not losses**, which took asking the person who owns the catalog. A third,
 * `m3/surface`, was on this list until the synthesised shelf stopped borrowing it
 * (yschimke/compose-ui-builder#81): there is no Surface in Remote Compose or Wear Material 3, and
 * the emitter tells an author to reach for a `layout/box` with `background` and `clip` instead. The
 * frozen catalog no longer offers it, so there is nothing left for the published one to lose.
 * - `m3/text` — **is** `RemoteText`, published here as `remote-m3/remote-text`. The component is
 *   not missing; the id a design stores changes.
 * - `remote-m3/lottie` — the catalog draws no Lottie sticker at all, so there is nothing here to
 *   publish. It is on the synthesised shelf because the server builds one from `asset/image`.
 *
 * So the shelf comparison is settled, and what remains is export — a different question, measured
 * below.
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
   * The record behind each published component, under the builder id a design names it with.
   *
   * The composition's own join rather than one this test performs. It used to read
   * `statusSemantics.components[id].record` out of the fixture and look the canonical id up itself,
   * which measured a map production never builds — the derived-id half of the join was missing
   * entirely, so an unannotated component could not appear. Now it is the same map `ServeRunner`
   * hands the export executor.
   */
  private val composedRecords: Map<String, ComponentRecord>
    get() = result.records

  /**
   * The ones the published catalog cannot offer, and why each one.
   *
   * Asserted ABSENT rather than skipped, and with the reason spelled out, because a gap nothing
   * states is a gap that stops being noticed. If one starts composing this test fails and says to
   * move it out, rather than passing quietly.
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
   * Everything the synthesised shelf offers that the donor will NOT put back — this catalog's own
   * components and the two it borrows from m3 alike. Both are the published file's to carry.
   */
  private fun frozenOwned() =
    frozen.components.filterNot { c -> donorNamespaces.any { c.componentId.startsWith(it) } }

  /**
   * Every component the pair composes, by name.
   *
   * A count floor was not enough and the review said so: at `size > 20` on a 27-component fixture,
   * six could vanish — `remote-button-group`, `remote-compact-button`, the progress indicators —
   * and no test here would notice, which is the one thing a pre-cutover gate exists to catch.
   * remote-catalog published TWO records before the scan classpath resolved AARs by coordinate, so
   * a regression of that size is the shape of the bug this is watching for.
   */
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
        // Not records: the host frames the policy declares as builtins, because the Glance Wear
        // container is a host frame rather than a `remote-material3` component and nothing calls
        // it. Everything above is a record component. `widget-container-adaptive` is the
        // experimental third, declared in remote-m3-catalog's policy: authored as headline /
        // supporting / action slots and resolved to Small or Large before anything renders.
        "remote-m3/widget-container-adaptive",
        "remote-m3/widget-container-large",
        "remote-m3/widget-container-small",
      ),
      composed.components.map { it.componentId }.filter { it.startsWith("remote-m3/") }.sorted(),
      "the composed remote-m3 shelf has changed",
    )
  }

  /**
   * `remote-sticker` is excluded, and the exclusion now reaches every place it has to.
   *
   * It is the frame every preview in this catalog is drawn inside rather than a component anyone
   * places in a design, so wear-m3-catalog#425 excludes it and publishes the reason. The exclusion
   * takes effect where it matters: it is not served, so it cannot be inserted, and the exporter is
   * no longer asked to write it.
   *
   * It used to remain on the MENU — the generator writing a palette entry for a component the
   * consumer refuses to serve, an item that disappeared on insert — and the third assertion here
   * pinned that as a known defect. yschimke/compose-ai-tools#5378 fixed it, the catalog has since
   * bumped to a release carrying the fix, and this re-capture is the first fixture written by it.
   *
   * That assertion's own note said to delete it once the fixture was re-captured. It is INVERTED
   * instead, and deliberately: deleting it would leave nothing watching a seam that has already
   * been wrong once, and the fix lives in another repository on a pin this one bumps. Inverted it
   * costs the same line and fails if a plugin bump ever puts the entry back. That is the opposite
   * of the shortening the note was guarding against — it asks for more than before, not less.
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
    // `capturing-wear-widget-preview` used to be the second entry. The catalog now uses the
    // RELEASED `CapturingWearWidgetPreview` (wear-m3-catalog#613) rather than its own copy, so the
    // wrapper is no longer a symbol of this module and drops out of the record entirely — which is
    // a stronger exclusion than a stated reason, not a weaker one.
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
   * The restored containers are the frozen ones, field by field — not just ids that match.
   *
   * A builtin is transcribed rather than derived, so "it composes" says nothing about whether it
   * composes into the right thing, and the id-set test above would pass on an empty shell. Every
   * field a design depends on is compared against the frozen shelf here: what the editor calls it,
   * what other components' slots can match on, what may go inside, and what a person can set.
   *
   * There is no divergence left. The last one was the `content` slot's `max: 1` — one design per
   * host — which a builtin could not state: `UiBuilderBuiltinSlot` said `required` and nothing
   * about a maximum, so the frozen bound composed unbounded and this list held two lines saying so.
   * Closed end to end: the policy schema grew `max` (yschimke/compose-ai-tools#5408), the reader
   * carries it into `SlotCardinalityV1` (#747), and the catalog declares it
   * (yschimke/wear-m3-catalog#446).
   *
   * So an empty list here is the whole of #674's first blocker: the two containers a Remote design
   * roots itself in are on the published shelf, and they are the frozen ones rather than something
   * that merely shares their ids.
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
   * The components reach the shelf at all, asserted so a discovery regression shows up here rather
   * than in the builder. Every one was invisible while the AAR carrying them was off the scan
   * classpath.
   *
   * Reaching the shelf is necessary and not sufficient — being offered says nothing about whether
   * an export can write the component; see the test below, which measures that separately.
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
   * Offered is not usable, and this measures one half of that.
   *
   * `RemoteContentEmitter.emit` is a `when` over component ids with a hand-written function each,
   * and it knows exactly the ids in [REMOTE_CONTENT_COMPONENT_IDS] — twelve, since
   * `remote-m3/remote-text` joined them. Not one of the OTHER twenty-five the published catalog
   * adds is among them, which was once the whole answer: a component you could insert and could not
   * export.
   *
   * It is no longer the whole answer. The emitter falls back to the component RECORD for a
   * component it has no case for, and twenty-two of the twenty-six now export — measured in
   * [what the published shelf can export is the reviewed set], which is the test to read for
   * whether the shelf is usable. This one stays because the hand-written cases and the fallback are
   * different mechanisms with different failure modes, and a case appearing here should be a
   * deliberate act rather than a surprise.
   *
   * WHICH lane a design takes is decided by its ROOT, not by its catalog:
   * `RecordFreeExport.applies` is `roots.single().componentId` being a `WearWidgetScaffoldSize` or
   * the Wear scaffold. So the two blockers here are coupled, and it is worth saying which way
   * round. A Remote design is rooted at a widget container — this catalog's policy says a Remote
   * design starts from one — and the widget containers are exactly what the published catalog
   * loses. Restore them and every design is record-free and meets this refusal head on; leave them
   * out and designs cannot be rooted correctly in the first place.
   *
   * Whether the record-driven lane is an escape was open here, and the record answers it. Its own
   * comment says `remote-m3` "ha[s] no component record and the record-driven generator can only
   * refuse them" — written when the record did not exist, which is the thing this work changed, so
   * the sentence needed re-checking rather than citing. Re-checked: the record exists and the
   * generator still refuses, and now it says why. `code.refusedReason` on 25 of the 27 names a
   * required parameter whose TYPE has no writable value — `RemoteString`, `RemoteBoolean`,
   * `RemoteFloat`, `Action`. A `remote-material3` component takes Remote Compose values, not Kotlin
   * ones, and neither generator can conjure one.
   *
   * That reframes the cost rather than removing it, and the reframing is the useful part: six
   * types, not twenty-five components — pinned as a histogram in
   * [the export blocker is six value types, not twenty-five components].
   *
   * Note what still cannot be measured from here, so nobody mistakes the histogram for a plan.
   * `ScreenGeneratorComposeExportExecutor` resolves a component through its own
   * `components(catalogSystemId)` source rather than through the `catalog` on the export request:
   * handed the real composed catalog it still answers "no component `remote-m3/remote-text` in this
   * catalog". A probe that does not wire that source measures its own fixture, which is what two
   * attempts at one did before this note replaced them.
   *
   * What is checkable without that wiring is the emitter's vocabulary, and that is what is asserted
   * below.
   *
   * `RemoteM3VocabularyParityTest` already holds the synthesised palette to this invariant — a
   * component you can insert and cannot export is worse than one that is missing, because the
   * author finds out at the end with the design already built. It does not cover a published
   * catalog, so the swap escapes it.
   *
   * Asserted as the exact current set rather than as `isEmpty()`, so the day the emitter learns
   * these components this test fails and says to shorten the list. Raised in review on #673.
   *
   * That day came for exactly one of them. `remote-m3/remote-text` now goes through the same
   * hand-written writer as the borrowed `m3/text`, because it is the same `RemoteText` call and the
   * record fallback could not write its type scale, its size or its alignment — six scalar types is
   * not a typography. So the list is one shorter, and the exclusion below is the statement that it
   * was shortened deliberately.
   *
   * Then it came for the rest of `RemoteMaterial3`. compose-ui-builder#202 taught the emitter to
   * write those components from their embedded record, and compose-ui-builder#214 put them in the
   * vocabulary, so against that checkout the list is down to what `RemoteMaterial3` leaves out on
   * purpose: `remote-icon` and the two page indicators, whose required parameters have no writable
   * value. The published 3.44.0 this repository pins has the record but not the vocabulary, so the
   * exclusion is taken only when the vocabulary actually names them — all of them or none, never a
   * partial list. Once the pin moves past 3.44.0 this can become the literal three.
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
   * Every modifier the published catalog advertises is one this lane can write.
   *
   * The invariant `RemoteM3VocabularyParityTest` holds the synthesised palette to, now true of a
   * published one: a control you can apply and cannot export is worse than one that is missing,
   * because the author finds out at the end with the design already built.
   *
   * It was false, and #673 pinned the three that broke it. Not one of the twenty-seven states
   * `modifierCapabilities`, so the composer handed each the structural default — and that default
   * is `ComponentRecordPacks`', which is a JETPACK COMPOSE default: `testTag` and `aspectRatio` on
   * everything, `shadow` on every container. `RemoteContentEmitter` writes none of the three, so
   * the shelf offered three controls whose use made every export of that design fail (#674 blocker
   * 3).
   *
   * Fixed by narrowing to what the catalog's declared `platform` can write rather than by
   * twenty-seven hand-written lists — which modifiers an emitter writes is the server's fact about
   * its own emitters, and the platform word is the catalog saying which emitter it is for. Asserted
   * as empty rather than as a set, because there is no longer a reviewed exception: a new entry
   * here is a regression, not a longer list.
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
   * What the export blocker actually costs, from the record rather than from a count of `when`
   * branches.
   *
   * "Twenty-five components means twenty-five hand-written emitter cases" is the shape of it from
   * the emitter's side, and it is the wrong unit. The record already answers the same question one
   * level down: `code.refusedReason` says why a component has no call site, and every refusal here
   * names a required parameter whose TYPE has no value a generator can write.
   *
   * Six types, not twenty-five components — and the fix is a type-directed mapping, which is what
   * `RemoteContentEmitter` already does for the modifier half (`modifierCalls` maps the catalog's
   * whole modifier vocabulary onto `RemoteModifier` generically, and refuses three by name). The
   * per-component half is `text`, `lottie`, `image` and the container argument builders, and what
   * they are doing by hand is turning a design's property into a Remote Compose value: a string
   * into a `RemoteString`, a colour into a `RemoteColor`.
   *
   * Two caveats this test does not let anyone skip:
   * - A refusal here is about a PLACEHOLDER — the snippet generator inventing a value out of
   *   nothing. An export has the design's real value, so `text: RemoteString` is not the same
   *   obstacle in both places. `Action` is: nine components require one and a design carries no
   *   action to map, so those need a policy before they need a mapper.
   * - None of this says the generated source compiles. That belongs in wear-m3-catalog against
   *   remote-material3's own classpath (`UI_BUILDER_CATALOG_CONTRACT.md` phase 2, item 11), and
   *   until it exists an emitter case is a claim rather than a fact.
   *
   * Asserted as the exact histogram so that teaching one type shows up as a number moving.
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
   * How much of the published shelf can now be EXPORTED, component by component.
   *
   * This is the invariant a cutover turns on and the one `RemoteM3VocabularyParityTest` holds the
   * synthesised palette to: a component you can insert and cannot export is worse than one that is
   * missing, because the author finds out at the end with the design already built. It did not
   * cover a published catalog, so the swap escaped it — and when this test was written the answer
   * was *none of the twenty-five*.
   *
   * It is not none any more. `RemoteContentEmitter` falls back to the component record for a
   * component it has no hand-written case for, mapping each parameter by its declared type, so the
   * question stopped being "has someone written a `when` branch" and became "can a design fill what
   * this component requires".
   *
   * Asked here the way an author would: for each component the catalog publishes, a widget whose
   * body is that component, with a value authored for every required parameter the design can
   * express. What still refuses is pinned with the reason the emitter gave, so teaching the mapper
   * one more type shortens this list and says so.
   *
   * The three the caller has to know about, and none of them is a mapping gap:
   * - `RemotePageIndicatorState` is a type no design value becomes;
   * - one callable is not public or internal, so no generated file can call it at all.
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

    // The denominator, pinned. Without this line a join that silently dropped four components
    // would report a shorter refusal list as an improvement. It is also what proves the
    // composition's join and the one this test used to perform by hand agree.
    //
    // Twenty-six, not the twenty-seven this was written with: wear-m3-catalog#425 excludes
    // `remote-sticker`, the frame every preview is drawn inside, so the shelf no longer offers
    // it and the exporter is no longer asked about it. Twenty-three of the twenty-six export: the
    // icon joined them when compose-ui-builder learned to write its glyph from an icon key.
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
   * Every shelf the components use has to be in the menu order, or the editor invents one.
   *
   * `UiBuilderEditorState` appends a group the order does not name after every group it does, and
   * sorts those alphabetically — so a catalog that states an order and then uses four groups
   * outside it has published an order the builder will not follow. `CatalogMenuTest` holds the
   * synthesised catalog to this; the generated pair is not there yet, which is the third blocker.
   *
   * Both directions are asserted, because they mean different things. An unordered group is a real
   * authoring gap and was one: `Selection buttons`, `Edge-hugging buttons`, `Sliders` and
   * `Steppers` are declared only in `src/snapshot/kotlin`, so the released-lane order never named
   * them and the snapshot lane silently re-sorted four shelves. Fixed in wear-m3-catalog. An
   * ORDERED group with no component is something else — see below. Raised in review on #673.
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
    // The other direction, and it is NOT an authoring gap — it is what one-id-per-symbol costs.
    //
    // `RemoteText` is drawn by 29 of this catalog's stickers, across Text, Buttons, Containment
    // and more. It is one record, so it gets one id and one shelf, and the shelf is the one the
    // sorted-first catalog id names: `AppCard`, hence Containment. `Text` is left with no
    // component of its own even though six stickers declare it, and `remote-m3/remote-text` sits
    // somewhere a person would not look for it.
    //
    // That is the trade the symbol-derived id makes deliberately — 49 stickers collapse to 27
    // components — and the policy's `group` is the sanctioned way for the catalog to place a
    // component whose stickers span sections. wear-m3-catalog#425 used it for six of them, which
    // is why `Text`, `Iconography` and `Position indicators` are no longer on this list: the
    // components that belong on them say so now instead of inheriting `AppCard`'s shelf.
    // Asserted as the exact current set so that placing one shortens this list and says so.
    //
    // The remaining seven are not placements anyone withheld. They hold stickers whose symbols
    // publish no component of their own, so there is nothing to place — `Widget Container` is the
    // exception and already has its two builtins, which the menu's component map does not list.
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
   * The builder's own vocabulary survives the swap, as it must for m3 — seven of the frozen
   * catalog's twelve entries are in a donor namespace. The other five are the gap above, and are
   * asserted there rather than here so the two questions do not blur into one number.
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

  private companion object {
    /**
     * The container differences that are reviewed rather than open, one line per container.
     *
     * The frozen shelf narrows the content slot to `RemoteAuthorable`
     * (yschimke/compose-ui-builder #81), a trait the synthesised catalog stamps on every component
     * a Remote widget can hold. The published catalog cannot say that: traits reach it from the
     * discovery RECORD, not from the policy, and no record component carries `RemoteAuthorable` —
     * so a published content slot narrowed to it would accept none of the catalog's own components.
     * `AnyContent` is the correct published value until the record can carry the trait. What it
     * gives up: the slot no longer refuses a donated `layout/`, `shape/`, `asset/` or
     * `remote-compose/` component the frozen shelf does not mark Remote-authorable. Delete a line
     * here when the published side carries the trait.
     */
    val REVIEWED_CONTAINER_DIFFERENCES =
      listOf("small", "large").map {
        "remote-m3/widget-container-$it.slots[content].acceptedTraits: " +
          "frozen=[RemoteAuthorable] composed=[AnyContent]"
      }

    /**
     * The three published components the widget exporter cannot write, and why each one.
     *
     * Twenty-three of the twenty-six do, which is the number this test exists to keep honest — and
     * it was reported as twenty-four until the emitter started checking whether a recovered
     * signature is a callable a generated file can reach. `remote-m3/theme-specimen` has a
     * signature and is not public, so writing the call from its parameters produced source that
     * imports and invokes something no other file may name. Found in review, and worth the count
     * moving the wrong way: a number that counts an uncompilable export as a success is what this
     * gate exists to stop.
     *
     * The other two are a required parameter whose TYPE no design value becomes, and neither is a
     * mapping the emitter could add without the design model growing a way to say it: the page
     * indicators want a `RemotePageIndicatorState`, a runtime object rather than a value. The icon
     * used to be the third, wanting a `RemoteImageVector`; compose-ui-builder now writes it by hand
     * from a Material icon key (`Icons.<Style>.<Name>.toRemoteImageVector()`), with the catalog's
     * `addCircle` when the design names none, so it exports (yschimke/remote-m3-catalog#12).
     */
    val EXPORT_REFUSALS =
      mapOf(
        "remote-m3/remote-horizontal-page-indicator" to
          "`state: RemotePageIndicatorState` and the design carries no value this generator can " +
            "write as one",
        "remote-m3/remote-vertical-page-indicator" to
          "`state: RemotePageIndicatorState` and the design carries no value this generator can " +
            "write as one",
        "remote-m3/theme-specimen" to
          "`remote-m3/theme-specimen` is not public or internal, so a generated file cannot " +
            "call it",
      )
  }
}
