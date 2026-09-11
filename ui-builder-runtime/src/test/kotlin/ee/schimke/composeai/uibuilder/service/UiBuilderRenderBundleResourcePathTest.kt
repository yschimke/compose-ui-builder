package ee.schimke.composeai.uibuilder.service

import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The seam between this module and `:ui-builder-render-bundle`, which is a string.
 *
 * The bundle is no longer a resource this module's own build copies in — it is a sibling artifact,
 * and the only thing joining the two is that both name
 * `ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png`. Nothing in Gradle
 * checks that: a rename on either side compiles, publishes, and fails at a consumer's first render
 * with "packaged UI-builder renderer bundle is missing".
 *
 * So the join is asserted here, in the module that declares the constant, against the artifact that
 * supplies the bytes. It is deliberately not folded into [ProductionUiBuilderRuntimeTest]: that
 * test exercises `copyTo`'s staging behaviour and would fail for this reason among several, where
 * this one can only fail for this reason.
 */
class UiBuilderRenderBundleResourcePathTest {

  @Test
  fun `the packaged bundle contains only the production renderer preview`() {
    val file = Files.createTempFile("ui-builder-renderer", ".png").toFile()
    try {
      PackagedUiBuilderRenderBundle::class
        .java
        .getResourceAsStream(PackagedUiBuilderRenderBundle.RESOURCE)!!
        .use { file.writeBytes(it.readBytes()) }
      ZipFile(file).use { zip ->
        val record =
          zip.getInputStream(zip.getEntry("previews.json")).bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
          }
        assertEquals(
          listOf(
            "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
          ),
          record.getValue("previews").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
          },
          "The preview plugin must not overwrite the production filter with all editor previews.",
        )
      }
    } finally {
      file.delete()
    }
  }

  @Test
  fun `the declared resource path resolves on the classpath`() {
    val stream =
      PackagedUiBuilderRenderBundle::class
        .java
        .getResourceAsStream(PackagedUiBuilderRenderBundle.RESOURCE)

    assertNotNull(
      stream,
      "no ${PackagedUiBuilderRenderBundle.RESOURCE} on the classpath — has " +
        ":ui-builder-render-bundle's staged resource path drifted from this constant?",
    )
    stream.use { assertTrue(it.readBytes().isNotEmpty(), "the packaged bundle is empty") }
  }

  @Test
  fun `the bundle states the Java version its classes need`() {
    // The same join as above, for the manifest beside the bundle. It fails the same way and is
    // worth no less: without it the server's preflight throws on a host that could have rendered.
    val required = PackagedUiBuilderRenderBundle.requiredJavaFeatureVersion()

    // Not asserted as a literal: the floor is `java-ui-builder` in the version catalog and a copy
    // here would be a second number to raise. What must hold is that it is a real feature version
    // and at least the floor every consumer of this module already clears, so that a manifest
    // written from a blank or truncated value fails here rather than at an operator's first render.
    assertTrue(required >= 17, "implausible javaMin in the bundle manifest: $required")
  }

  @Test
  fun `the resource path is absolute`() {
    // `getResourceAsStream` resolves a relative name against the calling class's package, which
    // would silently look for the bundle beside `PackagedUiBuilderRenderBundle` instead of at the
    // path the producing module stages it to.
    assertTrue(
      PackagedUiBuilderRenderBundle.RESOURCE.startsWith("/"),
      PackagedUiBuilderRenderBundle.RESOURCE,
    )
    assertTrue(
      PackagedUiBuilderRenderBundle.MANIFEST_RESOURCE.startsWith("/"),
      PackagedUiBuilderRenderBundle.MANIFEST_RESOURCE,
    )
  }
}
