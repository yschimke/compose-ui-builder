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
 * The shape table `ui-builder-equivalence.sh` validates against, written out of the REAL decoders.
 *
 * The gate is bash and inline node in a CI job with no JVM, so it cannot call the decoder it is
 * mirroring — and a mirror maintained by hand drifts. It did: a table transcribed by reading the
 * data classes in a sibling checkout missed six fields the PUBLISHED artifact carries
 * (`TargetParameter.noArgFactory`, `.scopeDslReceiver`, `.lambdaReturnTypeFqn`;
 * `BuilderPolicy.conflicting`, `.ambiguousWith`, `.malformed`), and a record setting one of them to
 * the wrong type was certified. The checkout was older than the dependency `:server` resolves, and
 * nothing said so.
 *
 * So the table is generated here, where the descriptors are the ones `PublishedUiBuilderCatalog`
 * will actually decode with, and committed for the node job to read. It moved here from
 * compose-preview-server with the composer: the `componentPolicy` decoder is internal to this
 * module, so only this repository can describe it. The server still generates the `record` half
 * from its own `ComponentRecordSource` decoder and carries this table's `componentPolicy` half.
 * This test fails the moment the dependency's shape changes, naming the field — which is the drift
 * check the hand-written version never had. Regenerate with `UPDATE_DECODER_SHAPES=1 ./gradlew
 * :ui-builder-runtime:test --tests '*DecoderShapeFixtureTest*'`.
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
   * One descriptor as the gate's `{kind, optional, nullable, members, values}` table.
   *
   * `seen` breaks reference cycles: a self-referential type would otherwise recurse forever, and a
   * table that cannot be written is better than one written wrong.
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
          // A `List<JsonElement>` — `allowedValues` is one. Its elements are whatever the property
          // is: strings for an enum, numbers for a range. Calling it `objectList` because it is
          // neither a string list nor a list of a known class made the gate demand an object per
          // entry and refuse every catalog that declares enum values — 104 findings against the
          // very first policy written to this contract, none of them real.
          else -> mapOf("kind" to JsonPrimitive("anyList"))
        }
      }
      StructureKind.CLASS -> {
        val described = describe(descriptor, seen)
        mapOf("kind" to JsonPrimitive("object")) +
          (described["members"]?.let { mapOf("members" to it) } ?: emptyMap())
      }
      // kotlinx decodes a map only from a JSON object, and each VALUE through the value type's
      // own serializer: `canvasMapping.properties` is a `Map<String, String>`, so `{"size": 7}`
      // fails to decode. The value's shape goes in `values` for the gate to check per entry. A
      // `JsonElement` value is the exception, free-form by design, so a map of those constrains
      // only the container: accepting a scalar there certifies a record the server refuses.
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
