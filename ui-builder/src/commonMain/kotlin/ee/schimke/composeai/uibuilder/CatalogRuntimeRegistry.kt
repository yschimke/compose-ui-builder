package ee.schimke.composeai.uibuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An immutable renderer bundle retained for reopening designs pinned to an older catalog. */
@Serializable
data class CatalogRuntimeDescriptor(
  val runtimeId: String,
  val catalogSystemId: String,
  val catalogRevision: String,
  val capabilityDigest: String,
  val protocolVersion: Int,
  val assetRoot: String,
  val integritySha256: String,
  val lifecycle: CatalogRuntimeLifecycle = CatalogRuntimeLifecycle.RETAINED,
)

@Serializable
enum class CatalogRuntimeLifecycle {
  RETAINED,
  DEPRECATED,
  RETIRED,
}

sealed interface CatalogRuntimeResolution {
  data class Ready(val runtime: CatalogRuntimeDescriptor) : CatalogRuntimeResolution

  data class MigrationRequired(
    val pinnedRuntimeId: String?,
    val reason: String,
    val availableRuntimeIds: List<String>,
  ) : CatalogRuntimeResolution

  data class InvalidPin(val issues: List<String>) : CatalogRuntimeResolution
}

/**
 * Resolves an exact saved catalog/runtime pin. There is deliberately no current/latest fallback:
 * absent, retired, or mismatched runtime assets require an explicit design migration.
 */
class CatalogRuntimeRegistry(
  descriptors: List<CatalogRuntimeDescriptor>,
  private val supportedProtocolVersions: Set<Int>,
) {
  private val byId = descriptors.associateBy(CatalogRuntimeDescriptor::runtimeId)

  init {
    require(descriptors.map { it.runtimeId }.distinct().size == descriptors.size) {
      "catalog runtime ids must be unique"
    }
    descriptors.forEach(::validateDescriptor)
  }

  fun resolve(catalogPin: JsonObject): CatalogRuntimeResolution {
    val expectedFields = setOf("systemId", "catalogRevision", "capabilityDigest", "nativeRuntimeId")
    val issues = mutableListOf<String>()
    val values = expectedFields.associateWith { field ->
      val value = (catalogPin[field] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
      if (value.isNullOrBlank()) issues += "catalogPin.$field must be nonblank text"
      value
    }
    (catalogPin.keys - expectedFields).sorted().forEach { field ->
      issues += "catalogPin contains unexpected field '$field'"
    }
    if (issues.isNotEmpty()) return CatalogRuntimeResolution.InvalidPin(issues)

    val runtimeId = checkNotNull(values["nativeRuntimeId"])
    val runtime =
      byId[runtimeId] ?: return migration(runtimeId, "the pinned native runtime is not retained")
    if (runtime.lifecycle == CatalogRuntimeLifecycle.RETIRED) {
      return migration(runtimeId, "the pinned native runtime is retired")
    }
    if (runtime.protocolVersion !in supportedProtocolVersions) {
      return migration(runtimeId, "the pinned native runtime protocol is unsupported")
    }
    val mismatches =
      listOf(
          "systemId" to runtime.catalogSystemId,
          "catalogRevision" to runtime.catalogRevision,
          "capabilityDigest" to runtime.capabilityDigest,
        )
        .mapNotNull { (field, expected) ->
          val actual = values.getValue(field)
          if (actual == expected) null
          else "catalogPin.$field '$actual' does not match runtime '$expected'"
        }
    if (mismatches.isNotEmpty()) return CatalogRuntimeResolution.InvalidPin(mismatches)
    return CatalogRuntimeResolution.Ready(runtime)
  }

  private fun migration(runtimeId: String, reason: String) =
    CatalogRuntimeResolution.MigrationRequired(
      pinnedRuntimeId = runtimeId,
      reason = reason,
      availableRuntimeIds =
        byId.values
          .filter {
            it.lifecycle != CatalogRuntimeLifecycle.RETIRED &&
              it.protocolVersion in supportedProtocolVersions
          }
          .map(CatalogRuntimeDescriptor::runtimeId)
          .sorted(),
    )

  private fun validateDescriptor(runtime: CatalogRuntimeDescriptor) {
    require(runtime.runtimeId.matches(Regex("[A-Za-z0-9._-]+"))) {
      "runtimeId must be safe for an immutable asset path"
    }
    require(runtime.catalogSystemId.isNotBlank()) { "catalogSystemId must be nonblank" }
    require(runtime.catalogRevision.isNotBlank()) { "catalogRevision must be nonblank" }
    require(runtime.capabilityDigest.isNotBlank()) { "capabilityDigest must be nonblank" }
    require(runtime.protocolVersion > 0) { "protocolVersion must be positive" }
    require(runtime.assetRoot == "/ui-builder/runtime/${runtime.runtimeId}/") {
      "runtime assetRoot must be the immutable version-addressed builder path"
    }
    require(runtime.integritySha256.matches(Regex("[a-f0-9]{64}"))) {
      "runtime integritySha256 must be a lowercase SHA-256 digest"
    }
  }
}

@Serializable data class RuntimePoint(val x: Float, val y: Float)

@Serializable
data class RuntimeRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Pure mapping between editor CSS coordinates and the isolated runtime viewport. Overlay code can
 * consume runtime measurements without becoming an ancestor of, or changing, the design layout.
 */
class CatalogRuntimeCoordinateMapper(
  private val editorSurface: RuntimeRect,
  private val runtimeViewport: RuntimeRect,
) {
  init {
    require(editorSurface.width > 0f && editorSurface.height > 0f)
    require(runtimeViewport.width > 0f && runtimeViewport.height > 0f)
  }

  fun editorToRuntime(point: RuntimePoint): RuntimePoint =
    RuntimePoint(
      x =
        runtimeViewport.x +
          (point.x - editorSurface.x) * runtimeViewport.width / editorSurface.width,
      y =
        runtimeViewport.y +
          (point.y - editorSurface.y) * runtimeViewport.height / editorSurface.height,
    )

  fun runtimeToEditor(rect: RuntimeRect): RuntimeRect =
    RuntimeRect(
      x =
        editorSurface.x +
          (rect.x - runtimeViewport.x) * editorSurface.width / runtimeViewport.width,
      y =
        editorSurface.y +
          (rect.y - runtimeViewport.y) * editorSurface.height / runtimeViewport.height,
      width = rect.width * editorSurface.width / runtimeViewport.width,
      height = rect.height * editorSurface.height / runtimeViewport.height,
    )
}
