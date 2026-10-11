package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Why [document] cannot be edited against [catalog], or null when it can. A design is pinned to one
 * catalog; opened against another (a host reading the wrong capability file), the palette would
 * offer components its runtime can't draw and its export can't write. The editor doesn't take the
 * host's choice on trust.
 */
fun catalogPinMismatch(document: UiBuilderDocument, catalog: CapabilityCatalog): String? {
  val pinned = document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull
  val offered = catalog.benchmark.catalogSystemId
  return when {
    pinned.isNullOrBlank() ->
      "The design names no catalog, so it cannot be opened against '$offered'"
    pinned != offered ->
      "The design is pinned to '$pinned' but was opened with the '$offered' catalog"
    else -> null
  }
}
