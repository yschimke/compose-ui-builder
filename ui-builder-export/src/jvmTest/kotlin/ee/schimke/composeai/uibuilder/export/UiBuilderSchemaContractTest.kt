package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderSchemaContractTest {
  private val json = Json { explicitNulls = false }

  @Test
  fun `mutation schema variants match the protocol serializer`() {
    val schema = schema("schemas/compose-ui-builder-mutation-v1.schema.json")
    val schemaTypes =
      schema["oneOf"]!!
        .jsonArray
        .map {
          it.jsonObject["properties"]!!
            .jsonObject["type"]!!
            .jsonObject["const"]!!
            .jsonPrimitive
            .content
        }
        .toSet()

    assertEquals(
      DesignMutationV1.serializer().descriptor.getElementDescriptor(1).elementNames.toSet(),
      schemaTypes,
    )
  }

  @Test
  fun `document schema required fields cover every committed fixture`() {
    val required =
      schema("schemas/compose-ui-builder-document-v1.schema.json")["required"]!!
        .jsonArray
        .map { it.jsonPrimitive.content }
        .toSet()
    val fixtureDirectory =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "docs/design/fixtures/ui-builder/designs") }
        .first(File::isDirectory)

    fixtureDirectory
      .listFiles { file -> file.extension == "json" }!!
      .forEach { fixture ->
        val document =
          UiBuilderReducer.replay(json.parseToJsonElement(fixture.readText()).jsonObject).document
        val encoded = json.encodeToString(document).let(json::parseToJsonElement).jsonObject
        assertTrue(required.all(encoded::containsKey), fixture.name)
      }
  }

  private fun schema(path: String) =
    json
      .parseToJsonElement(
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
          .bufferedReader()
          .readText()
      )
      .jsonObject
}
