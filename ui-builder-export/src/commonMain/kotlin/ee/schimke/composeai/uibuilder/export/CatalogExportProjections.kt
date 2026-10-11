package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ExportProjectionJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    val published =
      components.entries
        .mapNotNull { (id, policy) ->
          val projection = (policy as? JsonObject)?.get("exportProjection")
          if (projection == null || projection is JsonNull) return@mapNotNull null
          ExportProjectionJson.decode(projection).getOrNull()?.let { id to it }
        }
        .toMap()
    return published + builtinProjections(statusSemantics, components, published)
  }

  /**
   * A builtin exports through the projection of the record component its `implementation` names:
   * Wear's `wear-m3/transforming-lazy-column` is a builtin (it opens the `list` role and has no
   * shelf call site of its own), and the real `TransformingLazyColumn` it is written as is a record
   * component carrying the projection. The builtin's own id is what designs use, so the projection
   * is keyed by it. A builtin no component implements, or one whose implementation carries no
   * projection, exports as before.
   */
  private fun builtinProjections(
    statusSemantics: JsonObject,
    components: JsonObject,
    published: Map<String, ExportProjection>,
  ): Map<String, ExportProjection> {
    val builtins = statusSemantics["builtins"] as? JsonObject ?: return emptyMap()
    // Record canonical id → the projection of the one component published on it.
    val byRecord =
      components.entries
        .mapNotNull { (id, policy) ->
          val record = ((policy as? JsonObject)?.get("record") as? JsonPrimitive)?.contentOrNull
          val projection = published[id]
          if (record == null || projection == null) null else record to projection
        }
        .groupBy({ it.first }, { it.second })
        .mapNotNull { (record, projections) -> projections.singleOrNull()?.let { record to it } }
        .toMap()
    return builtins.entries
      .mapNotNull { (id, builtin) ->
        if (id in published) return@mapNotNull null
        val implementation =
          ((builtin as? JsonObject)?.get("implementation") as? JsonPrimitive)?.contentOrNull
        implementation?.let(byRecord::get)?.let { id to it }
      }
      .toMap()
  }
}
