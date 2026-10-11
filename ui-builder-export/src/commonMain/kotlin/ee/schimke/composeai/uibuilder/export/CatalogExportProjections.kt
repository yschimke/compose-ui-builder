package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ExportProjectionJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The export projections a catalog publishes: `statusSemantics.components[id].exportProjection`,
 * written by compose-ai-tools' `TypedAdapterCatalog` from a typed definition on the real library
 * records. Each says how that component's properties become a call to the real composable, so
 * [ScreenExportGate] needs no table of its own for it.
 *
 * A projection this build cannot read is left out rather than failing the catalog: the component
 * then exports as it did before projections existed, which is the same fallback every other
 * catalog-published policy reader takes.
 */
object CatalogExportProjections {
  fun from(statusSemantics: JsonObject): Map<String, ExportProjection> {
    val components = statusSemantics["components"] as? JsonObject ?: return emptyMap()
    return components.entries
      .mapNotNull { (id, policy) ->
        val published = (policy as? JsonObject)?.get("exportProjection")
        if (published == null || published is JsonNull) return@mapNotNull null
        ExportProjectionJson.decode(published).getOrNull()?.let { id to it }
      }
      .toMap()
  }
}
