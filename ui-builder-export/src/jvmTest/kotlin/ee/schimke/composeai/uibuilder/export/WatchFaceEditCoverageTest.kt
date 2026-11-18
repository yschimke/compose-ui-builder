package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Setting any property the catalog declares on an imported face must show in the XML, or be refused
 * by name: never silently dropped. Runs every declared property of every builtin on every node of
 * that builtin in the shipped templates and the sample faces.
 */
class WatchFaceEditCoverageTest {
  private val fixtures: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/wff-watchface").isDirectory }
      .let { File(it, "docs/design/fixtures/ui-builder") }

  private val builtins: Map<String, List<JsonObject>> =
    ((Json.parseToJsonElement(File(fixtures, "wff-watchface-published-v1.json").readText())
        as JsonObject)["statusSemantics"]
        as JsonObject)
      .let { it["builtins"] as JsonObject }
      .mapValues { (_, value) ->
        ((value as JsonObject)["properties"] as JsonArray).map { it as JsonObject }
      }

  private fun documents(): Map<String, UiBuilderDocument> {
    val faces = linkedMapOf<String, String>()
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val template =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          File(fixtures, "wff-watchface/designs/$name.json").readText(),
        )
      faces[name] =
        (WatchFaceXmlExporter.export(template) as WatchFaceXmlExporter.Result.Emitted).xml
    }
    File(
        fixtures.parentFile.parentFile.parentFile.parentFile,
        "ui-builder-export/src/jvmTest/resources/wear-os-samples",
      )
      .listFiles { f -> f.isDirectory }
      .orEmpty()
      .sortedBy { it.name }
      .forEach { faces[it.name] = File(it, "raw/watchface.xml").readText() }
    return faces.mapValues {
      assertIs<WatchFaceXmlImporter.Result.Imported>(
          WatchFaceXmlImporter.import(it.value, WatchFaceXmlImporter.Options(wffVersion = 5)),
        )
        .document
    }
  }

  /** The values to try: every allowed value but the current one, else one different value. */
  private fun candidates(
    property: JsonObject,
    current: String?,
    name: String,
  ): List<Pair<JsonObject, String>> {
    val allowed =
      (property["allowedValues"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
    if (allowed.isEmpty()) return listOf(encoded(property, current, name))
    val integer = (property["jsonType"] as JsonPrimitive).content == "integer"
    return allowed
      .filter { it != current }
      .map { value ->
        buildJsonObject {
          put("type", if (integer) "int" else "string")
          put("value", if (integer) JsonPrimitive(value.toInt()) else JsonPrimitive(value))
        } to value
      }
  }

  private fun encoded(
    property: JsonObject,
    current: String?,
    name: String,
  ): Pair<JsonObject, String> {
    val type = (property["jsonType"] as JsonPrimitive).content
    val allowed =
      (property["allowedValues"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
    fun literal(kind: String, value: JsonPrimitive) = buildJsonObject {
      put("type", kind)
      put("value", value)
    }
    return when {
      type == "boolean" ->
        literal("bool", JsonPrimitive(current != "true")) to (current != "true").toString()
      allowed.isNotEmpty() -> {
        val value = allowed.firstOrNull { it != current } ?: allowed.first()
        (if (type == "integer") literal("int", JsonPrimitive(value.toInt()))
        else literal("string", JsonPrimitive(value))) to value
      }
      type == "integer" || type == "number" -> {
        val value = if (current == "4242") "4243" else "4242"
        literal("int", JsonPrimitive(value.toInt())) to value
      }
      name == "xml" -> {
        val value =
          if (current?.contains("Edited1") == true) "<Edited2 a=\"1\"/>" else "<Edited1 a=\"1\"/>"
        literal("string", JsonPrimitive(value)) to value
      }
      PropertyValueKinds.isColour(name) -> {
        val value = if (current == "#123456") "#654321" else "#123456"
        literal("color", JsonPrimitive(value)) to value
      }
      name == "previewTime" -> {
        val value = if (current == "12:34:56") "01:02:03" else "12:34:56"
        literal("string", JsonPrimitive(value)) to value
      }
      else -> {
        val value = if (current == "zz-edit") "zz-edit2" else "zz-edit"
        literal("string", JsonPrimitive(value)) to value
      }
    }
  }

  /** Properties whose value is not written as text of its own (it picks a tag or a mode). */
  private val notWrittenAsText = setOf("type", "shape", "boundsType", "verbatim", "wffVersion")

  @Test
  fun `no declared property is silently dropped on an imported face`() {
    val offenders = sortedSetOf<String>()
    var checked = 0
    for ((face, document) in documents()) {
      val before = (WatchFaceXmlExporter.export(document) as WatchFaceXmlExporter.Result.Emitted)
      for ((component, properties) in builtins) {
        val nodes = document.nodes.values.filter { it.componentId == component }.take(3)
        for (node in nodes) {
          for (property in properties) {
            val name = (property["name"] as JsonPrimitive).content
            if (name == WatchFaceXmlExporter.EXTRAS) continue
            // Another shape's attribute is not written, by design: the shape it belongs to is
            // not this node's.
            if (name in WatchFaceXmlExporter.inapplicableProperties(node, document)) continue
            // One allowed value: there is no different valid value to try.
            if ((property["allowedValues"] as? JsonArray)?.size == 1) continue
            val current =
              ((node.properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.content
            val shown =
              candidates(property, current, name).any { (value, text) ->
                val edited =
                  document.copy(
                    nodes =
                      document.nodes +
                        (node.id to
                          node.copy(properties = JsonObject(node.properties + (name to value)))),
                  )
                checked++
                when (val result = WatchFaceXmlExporter.export(edited)) {
                  is WatchFaceXmlExporter.Result.Refused -> true
                  is WatchFaceXmlExporter.Result.Emitted ->
                    if (name == "wffVersion") result.wffVersion.toString() == text
                    else
                      result.xml != before.xml && (name in notWrittenAsText || text in result.xml)
                }
              }
            if (!shown) offenders += "$component.$name (face $face, node ${node.id})"
          }
        }
      }
    }
    assertTrue(checked > 100, "only $checked property edits were tried")
    assertEquals(emptyList(), offenders.toList(), "silently dropped: ${offenders.joinToString()}")
  }
}
