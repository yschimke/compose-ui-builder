package ee.schimke.composeai.uibuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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

/** Manifest served from an exact version-addressed runtime root. */
@Serializable
data class CatalogRuntimeManifest(
  val schema: String,
  val runtimeId: String,
  val protocolVersion: Int,
  val entrypoint: String,
  val integritySha256: String,
)

data class CatalogRuntimeManifestResponse(val statusCode: Int, val body: String)

fun interface CatalogRuntimeManifestTransport {
  suspend fun get(url: String): CatalogRuntimeManifestResponse
}

sealed interface CatalogRuntimeLoadResult {
  data class Ready(
    val runtime: CatalogRuntimeDescriptor,
    val manifest: CatalogRuntimeManifest,
    val entrypointUrl: String,
  ) : CatalogRuntimeLoadResult

  data class MigrationRequired(val pinnedRuntimeId: String, val reason: String) :
    CatalogRuntimeLoadResult

  data class InvalidRuntime(val pinnedRuntimeId: String, val reason: String) :
    CatalogRuntimeLoadResult
}

/**
 * Resolves the manifest and entrypoint for one exact runtime descriptor.
 *
 * The loader never probes another id and never rewrites the URL to a current/latest alias. A
 * missing or unsupported pin is therefore a migration decision rather than a silent pixel change.
 */
class CatalogRuntimeAssetLoader(
  private val supportedProtocolVersions: Set<Int>,
  private val transport: CatalogRuntimeManifestTransport,
) {
  suspend fun load(runtime: CatalogRuntimeDescriptor): CatalogRuntimeLoadResult {
    val descriptorProblem = validateRuntimeDescriptor(runtime)
    if (descriptorProblem != null) {
      return CatalogRuntimeLoadResult.InvalidRuntime(runtime.runtimeId, descriptorProblem)
    }
    if (runtime.lifecycle == CatalogRuntimeLifecycle.RETIRED) {
      return CatalogRuntimeLoadResult.MigrationRequired(
        runtime.runtimeId,
        "the pinned native runtime is retired",
      )
    }
    if (runtime.protocolVersion !in supportedProtocolVersions) {
      return CatalogRuntimeLoadResult.MigrationRequired(
        runtime.runtimeId,
        "the pinned native runtime protocol is unsupported",
      )
    }
    val manifestUrl = runtime.assetRoot + RUNTIME_MANIFEST_NAME
    val response = transport.get(manifestUrl)
    if (response.statusCode == 404) {
      return CatalogRuntimeLoadResult.MigrationRequired(
        runtime.runtimeId,
        "the pinned native runtime is not retained",
      )
    }
    if (response.statusCode !in 200..299) {
      return CatalogRuntimeLoadResult.InvalidRuntime(
        runtime.runtimeId,
        "runtime manifest request answered ${response.statusCode}",
      )
    }
    val manifest =
      try {
        RUNTIME_JSON.decodeFromString(CatalogRuntimeManifest.serializer(), response.body)
      } catch (_: SerializationException) {
        return CatalogRuntimeLoadResult.InvalidRuntime(
          runtime.runtimeId,
          "runtime manifest is not valid $RUNTIME_MANIFEST_SCHEMA JSON",
        )
      } catch (_: IllegalArgumentException) {
        return CatalogRuntimeLoadResult.InvalidRuntime(
          runtime.runtimeId,
          "runtime manifest is not valid $RUNTIME_MANIFEST_SCHEMA JSON",
        )
      }
    val mismatch =
      when {
        manifest.schema != RUNTIME_MANIFEST_SCHEMA -> "runtime manifest schema is unsupported"
        manifest.runtimeId != runtime.runtimeId -> "runtime manifest id does not match the pin"
        manifest.protocolVersion != runtime.protocolVersion ->
          "runtime manifest protocol does not match the pin"
        manifest.protocolVersion !in supportedProtocolVersions ->
          "the pinned native runtime protocol is unsupported"
        manifest.integritySha256 != runtime.integritySha256 ->
          "runtime manifest integrity does not match the pin"
        normalizeRuntimeAssetPath(manifest.entrypoint) != manifest.entrypoint ->
          "runtime manifest entrypoint is unsafe"
        else -> null
      }
    if (mismatch != null) {
      return CatalogRuntimeLoadResult.InvalidRuntime(runtime.runtimeId, mismatch)
    }
    return CatalogRuntimeLoadResult.Ready(
      runtime = runtime,
      manifest = manifest,
      entrypointUrl = runtime.assetRoot + manifest.entrypoint,
    )
  }

  private fun validateRuntimeDescriptor(runtime: CatalogRuntimeDescriptor): String? =
    when {
      !runtime.runtimeId.matches(SAFE_RUNTIME_ID) || runtime.runtimeId in RESERVED_RUNTIME_IDS ->
        "runtime id is unsafe or reserved"
      runtime.assetRoot != "/ui-builder/runtime/${runtime.runtimeId}/" ->
        "runtime asset root is not the exact version-addressed path"
      !runtime.integritySha256.matches(SHA256) -> "runtime integrity is not a lowercase SHA-256"
      else -> null
    }
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
    require(
      runtime.runtimeId.matches(SAFE_RUNTIME_ID) && runtime.runtimeId !in RESERVED_RUNTIME_IDS
    ) {
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

private const val RUNTIME_MANIFEST_SCHEMA = "compose-ui-builder-runtime/v1"
private const val RUNTIME_MANIFEST_NAME = "runtime-manifest.json"
private val SAFE_RUNTIME_ID = Regex("[A-Za-z0-9._-]+")
private val RESERVED_RUNTIME_IDS = setOf("current", "latest")
private val SHA256 = Regex("[a-f0-9]{64}")
private val RUNTIME_JSON = Json { ignoreUnknownKeys = false }

private fun normalizeRuntimeAssetPath(path: String): String? {
  if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) return null
  val segments = path.split('/')
  if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
  return segments.joinToString("/")
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
