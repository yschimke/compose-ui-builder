package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A widget body holding a Remote Material 3 component exports as that component's call, from the
 * record this build embeds — with no host supplying one.
 */
class RemoteMaterial3ExportTest {
  @Test
  fun `every component in the table has a callable record`() {
    assertEquals(
      RemoteMaterial3.components.map { it.componentId }.toSet(),
      RemoteMaterial3.records.keys,
    )
    RemoteMaterial3.records.forEach { (id, record) ->
      assertEquals(listOf(id), record.componentIds, id)
      assertTrue(record.signatureKnown && record.callableFromAnotherFile, id)
    }
  }

  @Test
  fun `every component exports as its own call once its required values are set`() {
    RemoteMaterial3.components.forEach { component ->
      val source = emitted(UiBuilderNode("body", component.componentId, properties = starters))
      val symbol = RemoteMaterial3.records.getValue(component.componentId).symbol
      File("build/remoteMaterial3/${symbol.name}.kt")
        .apply { parentFile.mkdirs() }
        .writeText(source)

      assertTrue("${symbol.name}(" in source || "${symbol.name} {" in source, source)
      assertTrue("import ${symbol.callable}" in source, source)
    }
  }

  @Test
  fun `a button writes its onClick placeholder and the text inside it`() {
    val source =
      emitted(
        UiBuilderNode(
          "body",
          "remote-m3/remote-button",
          slots = mapOf("content" to listOf("label")),
        ),
        "label" to
          UiBuilderNode(
            "label",
            "m3/text",
            properties = buildJsonObject { put("text", wrapped("string", JsonPrimitive("Play"))) },
          ),
      )

    assertTrue("onClick = lambdaAction {}" in source, source)
    assertTrue("RemoteText(text = \"Play\".rs)" in source, source)
  }

  /**
   * An icon is named by key and written as the vector `RemoteIcon` takes — the call a launcher
   * widget's icon buttons need (yschimke/remote-m3-catalog#12).
   */
  @Test
  fun `an icon writes its key as a Material vector converted for Remote Compose`() {
    val source =
      emitted(
        UiBuilderNode(
          "body",
          REMOTE_ICON_COMPONENT_ID,
          properties =
            buildJsonObject {
              put("imageVector", wrapped("enum", JsonPrimitive("home")))
              put("contentDescription", wrapped("string", JsonPrimitive("Home")))
              put("tint", wrapped("colorToken", JsonPrimitive("primary")))
            },
        )
      )

    assertTrue("imageVector = Icons.Filled.Home.toRemoteImageVector()" in source, source)
    assertTrue("contentDescription = \"Home\".rs" in source, source)
    assertTrue("tint = RemoteMaterialTheme.colorScheme.primary" in source, source)
    assertTrue("import androidx.compose.material.icons.Icons" in source, source)
    assertTrue("import androidx.compose.material.icons.filled.Home" in source, source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.capture.toRemoteImageVector" in source,
      source,
    )
  }

  @Test
  fun `an icon with no key draws the catalog's default`() {
    val source = emitted(UiBuilderNode("body", REMOTE_ICON_COMPONENT_ID))

    assertTrue("Icons.Filled.AddCircle.toRemoteImageVector()" in source, source)
    assertTrue("contentDescription = null" in source, source)
  }

  @Test
  fun `an icon key the table does not know is refused by name`() {
    val result =
      WearWidgetCodeExporter.export(
        widget(
          UiBuilderNode(
            "body",
            REMOTE_ICON_COMPONENT_ID,
            properties =
              buildJsonObject { put("imageVector", wrapped("enum", JsonPrimitive("not-an-icon"))) },
          )
        )
      )

    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(refused.reasons.any { "not-an-icon" in it }, refused.reasons.toString())
  }

  @Test
  fun `a host's own record for an id wins over the embedded one`() {
    val embedded = RemoteMaterial3.records.getValue("remote-m3/remote-card")
    val hosts =
      embedded
        .newBuilder()
        .apply {
          symbol =
            embedded.symbol
              .newBuilder()
              .apply {
                callable = "com.example.HostCard"
                name = "HostCard"
              }
              .build()
        }
        .build()
    val result =
      WearWidgetCodeExporter.export(
        widget(UiBuilderNode("body", "remote-m3/remote-card")),
        components = mapOf("remote-m3/remote-card" to hosts),
      )
    val source = assertIs<WearWidgetCodeExporter.Result.Emitted>(result).source

    assertTrue("HostCard(" in source, source)
    assertTrue("RemoteCard(" !in source, source)
  }

  /** The published catalog files each id against the same record this table names. */
  @Test
  fun `the ids and records are the published catalog's`() {
    val published =
      kotlinx.serialization.json.Json.parseToJsonElement(
          fixture("remote-m3-published-v1.json").readText()
        )
        .let { it as JsonObject }
        .getValue("statusSemantics")
        .let { it as JsonObject }
        .getValue("components") as JsonObject
    RemoteMaterial3.components.forEach { component ->
      if (component.componentId in UNPUBLISHED_REMOTE_M3_IDS) return@forEach
      val row = published[component.componentId] as? JsonObject
      assertEquals(
        component.recordId,
        (row?.get("record") as? JsonPrimitive)?.content,
        component.componentId,
      )
    }
  }

  private val starters = buildJsonObject {
    put("progress", wrapped("float", JsonPrimitive(0.5)))
    put("value", wrapped("float", JsonPrimitive(0.5)))
    put("checked", wrapped("bool", JsonPrimitive(true)))
    put("selected", wrapped("bool", JsonPrimitive(true)))
  }

  private fun wrapped(type: String, value: JsonPrimitive) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  private fun emitted(body: UiBuilderNode, vararg others: Pair<String, UiBuilderNode>): String =
    assertIs<WearWidgetCodeExporter.Result.Emitted>(
        WearWidgetCodeExporter.export(widget(body, *others)),
        "${body.componentId} refused",
      )
      .source

  private fun widget(body: UiBuilderNode, vararg others: Pair<String, UiBuilderNode>) =
    UiBuilderDocument(
      schema = "ui-builder-design-v1",
      id = "remote-material-3",
      title = "Remote Material 3",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("host"),
      nodes =
        mapOf(
          "host" to
            UiBuilderNode(
              "host",
              "remote-m3/widget-container-small",
              slots = mapOf("content" to listOf("body")),
            ),
          "body" to body,
        ) + others,
    )

  private fun fixture(name: String): File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder/$name") }
      .first { it.isFile }
}
