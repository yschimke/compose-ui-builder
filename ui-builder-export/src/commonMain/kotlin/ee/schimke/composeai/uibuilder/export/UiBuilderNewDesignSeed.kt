package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a new design starts as — one answer, for both callers who now ask the question.
 *
 * The browser asked it first, inline in its bootstrap: pick a template, pin it to the catalog being
 * served, seed the environment from the Jetcaster fixture. The server asks the same question now
 * that creating a design is a request it answers itself ([POST][.document] from the New design
 * form, or a `PUT` of a design that does not exist yet), and two implementations of "what does a
 * blank Wear widget look like?" would drift the first time a template changed. This is the same
 * module, and the same reasoning, as the export projection next to it: two very different callers
 * needing the same answer about one document.
 */
object UiBuilderNewDesignSeed {

  /**
   * The template a request that names none is asking for: [BLANK_TEMPLATE], which is also what an
   * owned `m3-catalog` defaults to (the first template its policy lists), so the default does not
   * change at the cutover. It was the Jetcaster fixture, re-pinned; see [FIXTURE_TEMPLATE].
   */
  const val DEFAULT_TEMPLATE: String = "blank"

  /**
   * The Jetcaster benchmark fixture re-pinned to the served catalog — the fallback [document]
   * answers for a template it does not recognise.
   *
   * No longer offered by [templateIds]. It is the builder's fidelity benchmark rather than a place
   * to start a design: it does not export (its carousel and state-comparison `selected` have no
   * Kotlin in the projection), and it uses `m3/snackbar-host`, which the published `m3-catalog`
   * deliberately does not carry, so on a catalog-served host it failed to create at all. Tests and
   * the benchmark seed it by this name.
   */
  const val FIXTURE_TEMPLATE: String = "jetcaster"

  /** An empty `ScreenScaffold` over an empty `TransformingLazyColumn`. */
  const val WEAR_SCREEN_TEMPLATE: String = "wear-screen"

  /** The same shape with wear-m3-catalog's own rows in it, which is what a parity check needs. */
  const val WEAR_LIST_TEMPLATE: String = "wear-list"

  /**
   * The blank screen with a headline and a line of text in it: the smallest design that already
   * shows something, for a person who wants to see the builder do anything before they learn it.
   */
  const val HELLO_TEMPLATE: String = "hello"

  /**
   * An A2UI surface: one `Column` root holding a headline. The column is the root because A2UI
   * draws a surface from a single component named `root`, and a column is what an agent almost
   * always sends there.
   */
  const val A2UI_TEMPLATE: String = "a2ui-column"

  /**
   * The templates [document] can seed for a catalog, which is what a caller is validated against.
   */
  fun templateIds(catalogSystemId: String): Set<String> =
    when (catalogSystemId) {
      "remote-m3" ->
        setOf("wear-widget-small", "wear-widget-large", AdaptiveWearWidget.TEMPLATE_ID) +
          WearWidgetSample.entries.map(WearWidgetSample::templateId) +
          RemoteClockTemplates.offered.map(RemoteClockTemplates::templateId)
      "wear-m3" -> setOf(WEAR_SCREEN_TEMPLATE, WEAR_LIST_TEMPLATE)
      A2uiDocumentExporter.CATALOG_SYSTEM_ID -> setOf(A2UI_TEMPLATE)
      LauncherWidgetTemplates.CATALOG_SYSTEM_ID -> LauncherWidgetTemplates.ids
      "m3-catalog" -> setOf(BLANK_TEMPLATE, HELLO_TEMPLATE) + AdaptiveScreenTemplates.ids
      else -> setOf(BLANK_TEMPLATE)
    }

  /**
   * The template every catalog can be seeded with when it publishes none of its own: the generic
   * blank screen, built from the builder's own layout vocabulary rather than any catalog's.
   */
  const val BLANK_TEMPLATE: String = "blank"

  /**
   * [templateIds] under the catalog-owned cutover. A catalog [ownership] names offers exactly the
   * templates it publishes, in its own order, or [BLANK_TEMPLATE] when it publishes none; any other
   * catalog keeps the built-in set, so this is [templateIds] while the flag is off.
   */
  fun templateIds(
    catalogSystemId: String,
    ownership: CatalogOwnership,
    published: CatalogSeedTemplates?,
  ): Set<String> =
    if (!ownership.owns(catalogSystemId)) templateIds(catalogSystemId)
    else
      published?.ids?.takeIf { it.isNotEmpty() }?.toCollection(LinkedHashSet())
        ?: setOf(BLANK_TEMPLATE)

  /**
   * [document] under the catalog-owned cutover: a catalog [ownership] names is seeded from the
   * template documents it publishes ([published]), never from a Kotlin builder that knows its ids.
   *
   * The published document keeps its own environment, because the catalog chose the device its seed
   * opens on (`frame.seedDevice`) and measured it; the pin is the served catalog's, exactly as
   * every built-in template is pinned. A catalog that publishes no templates gets the generic
   * [BLANK_TEMPLATE], and asking an owned catalog for a template it does not publish is refused by
   * name rather than answered with somebody else's.
   */
  fun document(
    designId: String,
    catalogSystemId: String,
    templateId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
    fixture: JsonObject,
    ownership: CatalogOwnership,
    published: CatalogSeedTemplates?,
    state: List<NewDesignState> = emptyList(),
  ): UiBuilderDocument {
    if (!ownership.owns(catalogSystemId)) {
      return document(
        designId,
        catalogSystemId,
        templateId,
        catalogRevision,
        nativeRuntimeId,
        fixture,
        state,
      )
    }
    require(designId.isNotBlank()) { "a new design needs an id" }
    require(published == null || published.catalogSystemId == catalogSystemId) {
      "templates published by ${published?.catalogSystemId} cannot seed $catalogSystemId"
    }
    val fixtureDocument = UiBuilderReducer.replay(fixture).document
    val catalogPin = servedPin(fixtureDocument, catalogSystemId, catalogRevision, nativeRuntimeId)
    published?.get(templateId)?.let {
      return it.seed(designId, catalogPin).withDeclaredState(state)
    }
    require(templateId == BLANK_TEMPLATE && published?.templates.isNullOrEmpty()) {
      "$catalogSystemId does not publish a `$templateId` template; it publishes " +
        (published?.ids?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "none")
    }
    return blankUiBuilderDocument(
      designId = designId,
      catalogPin = catalogPin,
      environment = mobileScreenEnvironment(fixtureDocument.environment),
      state = state,
    )
  }

  /**
   * [state] added to a published seed's own declarations, under the rule the built-in blank seed
   * applies, and refused where a requested name is one the template already declares: the person
   * asked for a variable the design would then silently not have.
   */
  private fun UiBuilderDocument.withDeclaredState(state: List<NewDesignState>): UiBuilderDocument {
    if (state.isEmpty()) return this
    requireExportableState(state)
    val clashes = state.map(NewDesignState::name).filter { it in stateVariables }
    require(clashes.isEmpty()) {
      "the template already declares ${clashes.joinToString { "`$it`" }}; choose another name"
    }
    val names = (stateVariables.keys + state.map(NewDesignState::name)).toList()
    require(names.map(::exportedStateIdentifier).distinct().size == names.size) {
      "state variable names must stay distinct once exported as Kotlin identifiers"
    }
    return copy(
      stateVariables = JsonObject(stateVariables + state.associate { it.name to it.declaration() })
    )
  }

  private fun servedPin(
    fixtureDocument: UiBuilderDocument,
    catalogSystemId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
  ): JsonObject =
    JsonObject(
      fixtureDocument.catalogPin.toMutableMap().also { pin ->
        pin["systemId"] = JsonPrimitive(catalogSystemId)
        pin["catalogRevision"] = JsonPrimitive(catalogRevision)
        pin["nativeRuntimeId"] = JsonPrimitive(nativeRuntimeId)
      }
    )

  /**
   * The document a design with this id, catalog and template begins life as, at revision zero.
   *
   * [fixture] is the Jetcaster operations fixture the builder ships beside its Wasm bundle. Every
   * template reads its environment from that fixture's own `createDesign` rather than restating a
   * default, and [FIXTURE_TEMPLATE] *is* it, re-pinned. The catalog pin is rewritten from the
   * catalog actually being served, because a document pinned to a revision this server does not
   * serve is one the service will refuse.
   */
  fun document(
    designId: String,
    catalogSystemId: String,
    templateId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
    fixture: JsonObject,
    state: List<NewDesignState> = emptyList(),
  ): UiBuilderDocument {
    require(designId.isNotBlank()) { "a new design needs an id" }
    val fixtureDocument = UiBuilderReducer.replay(fixture).document
    val catalogPin = servedPin(fixtureDocument, catalogSystemId, catalogRevision, nativeRuntimeId)
    val environment = fixtureDocument.environment
    val widgetSample = WearWidgetSample.forTemplate(templateId)
    val clock =
      RemoteClockTemplates.forTemplate(templateId)?.takeIf { it in RemoteClockTemplates.offered }
    return when {
      catalogSystemId == "remote-m3" && widgetSample != null ->
        widgetSample.document(
          designId = designId,
          catalogPin = catalogPin,
          environment = environment,
        )
      // The Wear frame, not the fixture's handset: the scaffold reads its diameter from the
      // document, so seeding a watch design on a phone would draw the smallest watch while the
      // Screen inspector said "Pixel".
      catalogSystemId == "wear-m3" && templateId == WEAR_LIST_TEMPLATE ->
        wearScreenUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = wearScreenEnvironment(environment),
        )
      catalogSystemId == "wear-m3" ->
        blankWearScreenUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = wearScreenEnvironment(environment),
        )
      // Whatever template was asked for: the A2UI catalog has one, and its palette holds nothing
      // the Material 3 templates below are made of.
      catalogSystemId == A2uiDocumentExporter.CATALOG_SYSTEM_ID ->
        a2uiUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = mobileScreenEnvironment(environment),
        )
      // Whatever template was asked for, as with A2UI: a launcher widget never starts on the phone
      // `blank` scaffold below, which its exporter refuses as a root.
      catalogSystemId == LauncherWidgetTemplates.CATALOG_SYSTEM_ID ->
        LauncherWidgetTemplates.document(
            templateId =
              templateId.takeIf { it in LauncherWidgetTemplates.ids }
                ?: LauncherWidgetTemplates.HELLO_TEMPLATE,
            designId = designId,
            catalogPin = catalogPin,
            environment = environment,
          )
          // The New design form's state, as the blank and catalog-owned seeds keep it.
          .withDeclaredState(state)
      // Remote content rather than a widget: its root is a box, so it opens on no host frame.
      catalogSystemId == "remote-m3" && clock != null ->
        clock
          .document(designId = designId, catalogPin = catalogPin, environment = environment)
          // The New design form's state, as the blank and launcher seeds keep it.
          .withDeclaredState(state)
      catalogSystemId == "remote-m3" && templateId == AdaptiveWearWidget.TEMPLATE_ID ->
        AdaptiveWearWidget.newDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = environment,
        )
      catalogSystemId == "remote-m3" ->
        wearWidgetUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = environment,
          size =
            if (templateId == "wear-widget-large") WearWidgetScaffoldSize.Large
            else WearWidgetScaffoldSize.Small,
        )
      catalogSystemId == "m3-catalog" && templateId in AdaptiveScreenTemplates.ids ->
        AdaptiveScreenTemplates.document(templateId, designId, catalogPin, environment)
      templateId == HELLO_TEMPLATE ->
        helloUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = mobileScreenEnvironment(environment),
        )
      templateId == "blank" ->
        blankUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          // A phone, not the fixture's 1280x800 supporting-pane canvas — see
          // [mobileScreenEnvironment]. [FIXTURE_TEMPLATE] below keeps the fixture's own frame,
          // which is the design it was built to show.
          environment = mobileScreenEnvironment(environment),
          state = state,
        )
      else -> fixtureDocument.copy(id = designId, revision = 0, catalogPin = catalogPin)
    }
  }
}

private val seedJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}

/**
 * The candidate document as the released v1 service document.
 *
 * A serialize/parse round trip rather than a field-by-field mapping: the two shapes are the same
 * shape, and a mapping written twice is a mapping that can disagree with itself.
 */
fun UiBuilderDocument.toDesignDocumentV1(): DesignDocumentV1 =
  seedJson
    .decodeFromString<DesignDocumentV1>(seedJson.encodeToString(this))
    .copy(home = home?.toDesignHomeV1())

/**
 * The released v1 service document as the candidate document, which is [toDesignDocumentV1]
 * backwards.
 *
 * The same serialize/parse round trip, for the same reason and now in the direction the *server*
 * needs: the record-free emitters in [RecordFreeExport] are written against [UiBuilderDocument]
 * because the browser editor authors one, and the export executor holds a `DesignDocumentV1`. A
 * second field-by-field mapping between two shapes that are the same shape is a mapping that can
 * disagree with the first.
 */
fun DesignDocumentV1.toUiBuilderDocument(): UiBuilderDocument =
  seedJson
    .decodeFromString<UiBuilderDocument>(seedJson.encodeToString(this))
    .copy(home = home?.toUiBuilderDocumentHome())

fun UiBuilderDocumentHome.toDesignHomeV1(): DesignHomeV1 =
  when (this) {
    is UiBuilderDocumentHome.Server -> DesignHomeV1.Server(url, designId)
    is UiBuilderDocumentHome.Repo -> DesignHomeV1.Repo(path)
  }

fun DesignHomeV1.toUiBuilderDocumentHome(): UiBuilderDocumentHome =
  when (this) {
    is DesignHomeV1.Server -> UiBuilderDocumentHome.Server(url, designId)
    is DesignHomeV1.Repo -> UiBuilderDocumentHome.Repo(path)
  }

/**
 * One node of the released document as the candidate node — [toUiBuilderDocument] for a node, so a
 * rule written once against [UiBuilderNode] (see `cardContentFill`) can be asked of a wire node
 * without a second copy of it.
 */
fun DesignNodeV1.toUiBuilderNode(): UiBuilderNode =
  seedJson.decodeFromString(seedJson.encodeToString(this))
