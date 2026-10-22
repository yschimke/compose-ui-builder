package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What a new design starts as — one answer shared by the browser, embedding hosts and the server's
 * create routes ([POST][.document], or a `PUT` of a new design), so templates cannot drift. Every
 * seed is a document the catalog publishes (`CatalogSeedTemplates`), or the generic blank for a
 * catalog that publishes none.
 */
object UiBuilderNewDesignSeed {

  /**
   * The Jetcaster benchmark fixture ([fixtureDocument]). No catalog publishes it: it does not
   * export and uses `m3/snackbar-host`, which the published `m3-catalog` lacks. Tests, the
   * benchmark and a host's sample picker seed it by name.
   */
  const val FIXTURE_TEMPLATE: String = "jetcaster"

  /**
   * The empty Wear screen template's id (a `ScreenScaffold` over an empty
   * `TransformingLazyColumn`), which the desktop's New design menu marks as a blank starter.
   */
  const val WEAR_SCREEN_TEMPLATE: String = "wear-screen"

  /**
   * The template every catalog can be seeded with when it publishes none of its own: the generic
   * blank screen, built from the builder's own layout vocabulary rather than any catalog's.
   */
  const val BLANK_TEMPLATE: String = "blank"

  /** Create from a validated publication without depending on the benchmark operations fixture. */
  fun document(
    designId: String,
    templateId: String,
    catalogPin: CatalogReferenceV1,
    published: CatalogSeedTemplates,
    state: List<NewDesignState> = emptyList(),
  ): UiBuilderDocument {
    require(designId.isNotBlank()) { "a new design needs an id" }
    require(published.catalogSystemId == catalogPin.systemId) {
      "templates published by ${published.catalogSystemId} cannot seed ${catalogPin.systemId}"
    }
    val pin = seedJson.encodeToJsonElement(CatalogReferenceV1.serializer(), catalogPin).jsonObject
    published[templateId]?.let {
      return it.seed(designId, pin).withDeclaredState(state)
    }
    require(templateId == BLANK_TEMPLATE && published.templates.isEmpty()) {
      "${catalogPin.systemId} does not publish a `$templateId` template"
    }
    return blankUiBuilderDocument(
      designId,
      pin,
      mobileScreenEnvironment(
        seedJson
          .parseToJsonElement(
            """{"theme":"light","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"}"""
          )
          .jsonObject
      ),
      state,
    )
  }

  /**
   * [state] added to a published seed's declarations, refused where a requested name collides with
   * one the template already declares.
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

  /**
   * The Jetcaster benchmark fixture ([FIXTURE_TEMPLATE]) as design [designId], at revision zero and
   * pinned to the served [catalogPin]. No catalog publishes it, so a host asking for it by name
   * sends the fixture shipped beside the Wasm bundle instead of a template set. Only the catalog
   * the fixture was made against can seed it: re-pinning its components to another catalog would
   * hide that they are not that catalog's.
   */
  fun fixtureDocument(
    designId: String,
    catalogPin: CatalogReferenceV1,
    fixture: JsonObject,
  ): UiBuilderDocument {
    require(designId.isNotBlank()) { "a new design needs an id" }
    val document = UiBuilderReducer.replay(fixture).document
    val madeFor = (document.catalogPin["systemId"] as? JsonPrimitive)?.content
    require(madeFor == catalogPin.systemId) {
      "the `$FIXTURE_TEMPLATE` fixture is a $madeFor design and cannot seed ${catalogPin.systemId}"
    }
    val pin = seedJson.encodeToJsonElement(CatalogReferenceV1.serializer(), catalogPin).jsonObject
    return document.copy(id = designId, revision = 0, catalogPin = pin)
  }
}

private val seedJson = Json {
  classDiscriminator = "type"
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}

/**
 * The candidate document as the released v1 service document, via a serialize/parse round trip (the
 * shapes are identical, so a hand mapping could only diverge).
 */
fun UiBuilderDocument.toDesignDocumentV1(): DesignDocumentV1 =
  seedJson
    .decodeFromString<DesignDocumentV1>(seedJson.encodeToString(this))
    .copy(home = home?.toDesignHomeV1())

/**
 * [toDesignDocumentV1] backwards: the server's export executor holds a `DesignDocumentV1`, while
 * the record-free emitters in [RecordFreeExport] take a [UiBuilderDocument].
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
 * One wire node as the candidate node, so a rule written against [UiBuilderNode] (e.g.
 * `cardContentFill`) can be asked of it.
 */
fun DesignNodeV1.toUiBuilderNode(): UiBuilderNode =
  seedJson.decodeFromString(seedJson.encodeToString(this))
