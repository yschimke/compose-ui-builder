package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CatalogRuntimeRegistryTest {
  private val retained = runtime("runtime-1", "catalog-1", "digest-1")
  private val next = runtime("runtime-2", "catalog-2", "digest-2")

  @Test
  fun `old designs reopen through their exact retained runtime`() {
    val registry = CatalogRuntimeRegistry(listOf(retained, next), setOf(1))

    val resolved = assertIs<CatalogRuntimeResolution.Ready>(registry.resolve(pin(retained)))

    assertEquals(retained, resolved.runtime)
  }

  @Test
  fun `missing retired and unsupported runtimes require explicit migration`() {
    val retired =
      runtime("runtime-old", "catalog-old", "digest-old")
        .copy(lifecycle = CatalogRuntimeLifecycle.RETIRED)
    val unsupported =
      runtime("runtime-new-protocol", "catalog-3", "digest-3").copy(protocolVersion = 2)
    val registry = CatalogRuntimeRegistry(listOf(retired, unsupported, next), setOf(1))

    listOf(pin(retired), pin(unsupported), pin(retained)).forEach { catalogPin ->
      val result =
        assertIs<CatalogRuntimeResolution.MigrationRequired>(registry.resolve(catalogPin))
      assertEquals(listOf("runtime-2"), result.availableRuntimeIds)
    }
  }

  @Test
  fun `runtime identity never hides catalog or capability drift`() {
    val registry = CatalogRuntimeRegistry(listOf(retained), setOf(1))
    val drifted = JsonObject(pin(retained) + ("capabilityDigest" to JsonPrimitive("other")))

    val invalid = assertIs<CatalogRuntimeResolution.InvalidPin>(registry.resolve(drifted))

    assertTrue(invalid.issues.single().contains("does not match"))
  }

  @Test
  fun `pin and descriptor shapes fail closed`() {
    val registry = CatalogRuntimeRegistry(listOf(retained), setOf(1))
    val incomplete = JsonObject(mapOf("nativeRuntimeId" to JsonPrimitive(retained.runtimeId)))

    assertIs<CatalogRuntimeResolution.InvalidPin>(registry.resolve(incomplete))
    assertFailsWith<IllegalArgumentException> {
      CatalogRuntimeRegistry(
        listOf(retained.copy(assetRoot = "/mutable/latest/")),
        supportedProtocolVersions = setOf(1),
      )
    }
  }

  @Test
  fun `surface mapping is reversible without changing runtime geometry`() {
    val mapper =
      CatalogRuntimeCoordinateMapper(
        editorSurface = RuntimeRect(100f, 50f, 640f, 400f),
        runtimeViewport = RuntimeRect(0f, 0f, 1280f, 800f),
      )
    val runtimeBounds = RuntimeRect(240f, 160f, 320f, 120f)

    assertEquals(RuntimePoint(320f, 200f), mapper.editorToRuntime(RuntimePoint(260f, 150f)))
    assertEquals(RuntimeRect(220f, 130f, 160f, 60f), mapper.runtimeToEditor(runtimeBounds))

    val mappedTopLeft = mapper.runtimeToEditor(RuntimeRect(320f, 200f, 0.01f, 0.01f))
    assertEquals(260f, mappedTopLeft.x)
    assertEquals(150f, mappedTopLeft.y)
    assertEquals(runtimeBounds, RuntimeRect(240f, 160f, 320f, 120f))
  }

  private fun runtime(id: String, revision: String, digest: String) =
    CatalogRuntimeDescriptor(
      runtimeId = id,
      catalogSystemId = "m3-catalog",
      catalogRevision = revision,
      capabilityDigest = digest,
      protocolVersion = 1,
      assetRoot = "/ui-builder/runtime/$id/",
      integritySha256 = "a".repeat(64),
    )

  private fun pin(runtime: CatalogRuntimeDescriptor) =
    JsonObject(
      mapOf(
        "systemId" to JsonPrimitive(runtime.catalogSystemId),
        "catalogRevision" to JsonPrimitive(runtime.catalogRevision),
        "capabilityDigest" to JsonPrimitive(runtime.capabilityDigest),
        "nativeRuntimeId" to JsonPrimitive(runtime.runtimeId),
      )
    )
}
