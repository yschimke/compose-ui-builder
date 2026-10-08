package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `remote-widgets` hello template: a launcher widget a new design can start from, which exports
 * as a `RemoteComposeWidget` without a single edit.
 */
class LauncherWidgetTemplatesTest {

  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val pin =
    JsonObject(
      mapOf(
        "systemId" to JsonPrimitive("remote-widgets"),
        "catalogRevision" to JsonPrimitive("candidate"),
        "capabilityDigest" to JsonPrimitive("candidate"),
        "nativeRuntimeId" to JsonPrimitive("candidate"),
      )
    )

  private fun hello(): UiBuilderDocument =
    LauncherWidgetTemplates.document(
      templateId = LauncherWidgetTemplates.HELLO_TEMPLATE,
      designId = "hello",
      catalogPin = pin,
      environment = JsonObject(mapOf("locale" to JsonPrimitive("en-US"))),
    )

  @Test
  fun `the catalog offers the hello template instead of the phone blank`() {
    assertEquals(
      setOf(LauncherWidgetTemplates.HELLO_TEMPLATE),
      UiBuilderNewDesignSeed.templateIds("remote-widgets"),
    )
  }

  @Test
  fun `it starts on the launcher widget root at a grid size`() {
    val document = hello()
    assertEquals(
      LauncherWidgetCodeExporter.ROOT,
      document.nodes.getValue(document.roots.single()).componentId,
    )
    assertEquals(
      LauncherWidgetTemplates.HELLO_SIZE,
      LauncherWidgetGrid.of(
        document.environment.getValue("widthDp").jsonPrimitive.int,
        document.environment.getValue("heightDp").jsonPrimitive.int,
      ),
    )
    // The rest of the environment is the caller's.
    assertEquals("en-US", document.environment.getValue("locale").jsonPrimitive.content)
  }

  @Test
  fun `it exports as a RemoteComposeWidget with no edit`() {
    val generated =
      RecordFreeExport.generate(
        hello(),
        UiBuilderCatalogPlatform.REMOTE_COMPOSE,
        packageName = "hello",
      )
    assertIs<RecordFreeExport.Generated.Emitted>(generated, generated.toString())
    val source = generated.source
    assertContains(source, "class HelloWidget : RemoteComposeWidget() {")
    assertContains(source, "text = \"Hello, World!\".rs")
    assertContains(source, "@Preview(name = \"3x1\", widthDp = 203, heightDp = 102)")
    assertContains(source, "import androidx.compose.remote.creation.compose.layout.RemoteText")
    assertFalse("RemoteMaterialTheme" in source, source)
  }
}
