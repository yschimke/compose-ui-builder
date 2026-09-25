package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Why [document] cannot be edited against [catalog], or null when it can.
 *
 * A design is pinned to one catalog, and every component in it, every template it came from and
 * every export it goes to is that catalog's. Opened against another — a host that read the wrong
 * capability file — the list would offer the other catalog's components, and a remote-m3 widget
 * would fill with m3 components its runtime cannot draw and its export cannot write. The host
 * chooses the catalog; this is the editor refusing to take that choice on trust.
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
