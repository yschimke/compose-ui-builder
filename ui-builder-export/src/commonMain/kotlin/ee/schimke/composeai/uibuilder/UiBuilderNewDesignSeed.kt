package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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

  /** The template a URL that names none is asking for. */
  const val DEFAULT_TEMPLATE: String = "jetcaster"

  /**
   * The templates [document] can seed for a catalog, which is what a caller is validated against.
   */
  fun templateIds(catalogSystemId: String): Set<String> =
    when (catalogSystemId) {
      "remote-m3" ->
        setOf("wear-widget-small", "wear-widget-large") +
          WearWidgetSample.entries.map(WearWidgetSample::templateId)
      else -> setOf("blank", DEFAULT_TEMPLATE)
    }

  /**
   * The document a design with this id, catalog and template begins life as, at revision zero.
   *
   * [fixture] is the Jetcaster operations fixture the builder ships beside its Wasm bundle. Every
   * template reads its environment from that fixture's own `createDesign` rather than restating a
   * default, and the `jetcaster` template *is* it, re-pinned. The catalog pin is rewritten from the
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
    val catalogPin =
      JsonObject(
        fixtureDocument.catalogPin.toMutableMap().also { pin ->
          pin["systemId"] = JsonPrimitive(catalogSystemId)
          pin["catalogRevision"] = JsonPrimitive(catalogRevision)
          pin["nativeRuntimeId"] = JsonPrimitive(nativeRuntimeId)
        }
      )
    val environment = fixtureDocument.environment
    val widgetSample = WearWidgetSample.forTemplate(templateId)
    return when {
      catalogSystemId == "remote-m3" && widgetSample != null ->
        widgetSample.document(
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
      templateId == "blank" ->
        blankUiBuilderDocument(
          designId = designId,
          catalogPin = catalogPin,
          environment = environment,
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
  seedJson.decodeFromString(seedJson.encodeToString(this))
