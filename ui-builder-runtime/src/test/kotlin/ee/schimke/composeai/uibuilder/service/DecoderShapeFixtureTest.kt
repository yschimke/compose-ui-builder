package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * The shape table `ui-builder-equivalence.sh` validates against, generated from the real decoders
 * (the gate runs without a JVM, and a hand-maintained mirror drifted from the published
 * artifacts). Committed for the node job to read; fails when the dependency's shape changes, naming
 * the field. The server generates the `record` half itself.
 *
 * Regenerate with `UPDATE_DECODER_SHAPES=1 ./gradlew :ui-builder-runtime:test --tests
 * '*DecoderShapeFixtureTest*'`.
 */
class DecoderShapeFixtureTest {

  @Test
  fun `the committed shape table matches the decoders`() {
    val generated = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), shapes())
    val fixture = Path.of("..", ".github", "scripts", "decoder-shapes.json").normalize()
    if (System.getenv("UPDATE_DECODER_SHAPES") == "1") {
      Files.createDirectories(fixture.parent)
      Files.writeString(fixture, generated + "\n")
      return
    }
    val committed = Files.readString(fixture).trim()
    assertEquals(
      generated.trim(),
      committed,
      "The decoders changed shape. Regenerate with " +
        "UPDATE_DECODER_SHAPES=1 ./gradlew :ui-builder-runtime:test --tests '*DecoderShapeFixtureTest*' " +
        "and commit .github/scripts/decoder-shapes.json — the " +
        "readiness gate validates records against this table and cannot see the decoders itself.",
    )
  }

  @Test
  fun `canvas adapter maps require JSON objects`() {
    val mapping =
      shapes()["componentPolicy"]
        ?.jsonObject
        ?.get("members")
        ?.jsonObject
        ?.get("canvasMapping")
        ?.jsonObject
        ?.get("members")
        ?.jsonObject
    listOf("defaults", "properties", "slots").forEach { name ->
      assertEquals("object", mapping?.get(name)?.jsonObject?.get("kind")?.jsonPrimitive?.content)
    }
  }

  private fun shapes(): JsonObject =
    JsonObject(
      mapOf(
        // What `ComponentRecordSource` decodes components.json as.
        "record" to describe(serializer<ComponentRecordFile>().descriptor, mutableSetOf()),
        // And what `PublishedUiBuilderCatalog` decodes each statusSemantics.components VALUE as.
        "componentPolicy" to
          describe(
            serializer<PublishedUiBuilderCatalog.UiBuilderComponentPolicy>().descriptor,
            mutableSetOf(),
          ),
      )
    )

  /**
   * One descriptor as the gate's `{kind, optional, nullable, members, values}` table. `seen` breaks
   * reference cycles.
   */
  private fun describe(descriptor: SerialDescriptor, seen: MutableSet<String>): JsonObject {
    val members = mutableMapOf<String, JsonObject>()
    if (!seen.add(descriptor.serialName)) return JsonObject(mapOf("kind" to JsonPrimitive("any")))
    for (index in 0 until descriptor.elementsCount) {
      val element = descriptor.getElementDescriptor(index)
      val entry = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
      entry += kindOf(element, seen)
      // The two facts the gate needs and kotlinx answers separately: an ABSENT key takes the
      // property's default when it has one, while an explicit NULL decodes only for a nullable
      // type. Conflating them is the mistake this table exists to make impossible.
      if (descriptor.isElementOptional(index)) entry["optional"] = JsonPrimitive(true)
      if (element.isNullable) entry["nullable"] = JsonPrimitive(true)
      members[descriptor.getElementName(index)] = JsonObject(entry)
    }
    seen.remove(descriptor.serialName)
    return JsonObject(
      mapOf("kind" to JsonPrimitive("object"), "members" to JsonObject(members.toSortedMap()))
    )
  }

  private fun kindOf(
    descriptor: SerialDescriptor,
    seen: MutableSet<String>,
  ): Map<String, kotlinx.serialization.json.JsonElement> =
    when (descriptor.kind) {
      PrimitiveKind.STRING -> mapOf("kind" to JsonPrimitive("string"))
      PrimitiveKind.BOOLEAN -> mapOf("kind" to JsonPrimitive("boolean"))
      PrimitiveKind.INT -> mapOf("kind" to JsonPrimitive("int"))
      SerialKind.ENUM ->
        mapOf(
          "kind" to JsonPrimitive("enum"),
          "values" to
            JsonArray(
              (0 until descriptor.elementsCount).map {
                JsonPrimitive(descriptor.getElementName(it))
              }
            ),
        )
      StructureKind.LIST -> {
        val item = descriptor.getElementDescriptor(0)
        when (item.kind) {
          PrimitiveKind.STRING -> mapOf("kind" to JsonPrimitive("stringList"))
          StructureKind.CLASS ->
            mapOf(
              "kind" to JsonPrimitive("objectList"),
              "members" to describe(item, seen).getValue("members"),
            )
          // A `List<JsonElement>` (e.g. `allowedValues`): elements may be strings or numbers, so it
          // is not an `objectList`.
          else -> mapOf("kind" to JsonPrimitive("anyList"))
        }
      }
      StructureKind.CLASS -> {
        val described = describe(descriptor, seen)
        mapOf("kind" to JsonPrimitive("object")) +
          (described["members"]?.let { mapOf("members" to it) } ?: emptyMap())
      }
      // kotlinx decodes a map only from an object, and each value via its own serializer, so the
      // value shape goes in `values`. `JsonElement` values are free-form, constraining only the
      // container.
      StructureKind.MAP -> {
        val value = descriptor.getElementDescriptor(1)
        val spec = kindOf(value, seen)
        if (spec["kind"] == JsonPrimitive("any")) mapOf("kind" to JsonPrimitive("object"))
        else
          mapOf(
            "kind" to JsonPrimitive("object"),
            "values" to
              JsonObject(
                if (value.isNullable) spec + ("nullable" to JsonPrimitive(true)) else spec
              ),
          )
      }
      // A free-form JsonElement is carried but not constrained.
      else -> mapOf("kind" to JsonPrimitive("any"))
    }
}
