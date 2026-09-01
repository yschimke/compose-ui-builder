package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class CatalogRuntimeAssetLoaderTest {
  @Test
  fun `loader fetches only the exact pinned manifest and returns its entrypoint`() = runBlocking {
    val requested = mutableListOf<String>()
    val runtime = runtime("m3-2026.09")
    val loader =
      CatalogRuntimeAssetLoader(setOf(1)) { url ->
        requested += url
        CatalogRuntimeManifestResponse(200, manifest(runtime, "renderer/index.html"))
      }

    val ready = assertIs<CatalogRuntimeLoadResult.Ready>(loader.load(runtime))

    assertEquals(
      listOf("/ui-builder/runtime/m3-2026.09/runtime-manifest.json"),
      requested,
    )
    assertEquals(
      "/ui-builder/runtime/m3-2026.09/renderer/index.html",
      ready.entrypointUrl,
    )
  }

  @Test
  fun `missing exact pin requires migration without probing latest`() = runBlocking {
    val requested = mutableListOf<String>()
    val runtime = runtime("retained-old")
    val loader =
      CatalogRuntimeAssetLoader(setOf(1)) { url ->
        requested += url
        CatalogRuntimeManifestResponse(404, "not found")
      }

    val missing = assertIs<CatalogRuntimeLoadResult.MigrationRequired>(loader.load(runtime))

    assertEquals("retained-old", missing.pinnedRuntimeId)
    assertEquals(
      listOf("/ui-builder/runtime/retained-old/runtime-manifest.json"),
      requested,
    )
  }

  @Test
  fun `loader fails closed on protocol integrity identity and entrypoint drift`() = runBlocking {
    val runtime = runtime("m3-runtime")
    val manifests =
      listOf(
        manifest(runtime.copy(runtimeId = "other"), "index.html"),
        manifest(runtime.copy(protocolVersion = 2), "index.html"),
        manifest(runtime.copy(integritySha256 = "b".repeat(64)), "index.html"),
        manifest(runtime, "../index.html"),
      )

    manifests.forEach { body ->
      val result =
        CatalogRuntimeAssetLoader(setOf(1)) { CatalogRuntimeManifestResponse(200, body) }
          .load(runtime)
      assertIs<CatalogRuntimeLoadResult.InvalidRuntime>(result)
    }

    var requested = false
    val unsupported =
      CatalogRuntimeAssetLoader(setOf(1)) {
          requested = true
          CatalogRuntimeManifestResponse(200, manifest(runtime, "index.html"))
        }
        .load(runtime.copy(protocolVersion = 2))
    assertIs<CatalogRuntimeLoadResult.MigrationRequired>(unsupported)
    assertEquals(false, requested)
  }

  @Test
  fun `reserved aliases are invalid before any network request`() = runBlocking {
    var requested = false
    val loader =
      CatalogRuntimeAssetLoader(setOf(1)) {
        requested = true
        CatalogRuntimeManifestResponse(200, "{}")
      }

    val result = assertIs<CatalogRuntimeLoadResult.InvalidRuntime>(loader.load(runtime("latest")))

    assertEquals("latest", result.pinnedRuntimeId)
    assertEquals(false, requested)
  }

  private fun runtime(id: String) =
    CatalogRuntimeDescriptor(
      runtimeId = id,
      catalogSystemId = "m3-catalog",
      catalogRevision = "2026.09",
      capabilityDigest = "capability-digest",
      protocolVersion = 1,
      assetRoot = "/ui-builder/runtime/$id/",
      integritySha256 = "a".repeat(64),
    )

  private fun manifest(runtime: CatalogRuntimeDescriptor, entrypoint: String): String =
    """{"schema":"compose-ui-builder-runtime/v1","runtimeId":"${runtime.runtimeId}","protocolVersion":${runtime.protocolVersion},"entrypoint":"$entrypoint","integritySha256":"${runtime.integritySha256}"}"""
}
