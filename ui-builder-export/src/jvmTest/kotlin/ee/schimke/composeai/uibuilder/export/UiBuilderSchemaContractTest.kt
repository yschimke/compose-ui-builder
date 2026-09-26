package ee.schimke.composeai.uibuilder.export

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderSchemaContractTest {
  private val json = Json { explicitNulls = false }

  @Test
  fun `mutation schema variants and required fields match the protocol serializer`() {
    val schema = schema("schemas/compose-ui-builder-mutation-v1.schema.json")
    val schemaVariants =
      schema["oneOf"]!!.jsonArray.associate { variant ->
        val body = variant.jsonObject
        val type =
          body["properties"]!!.jsonObject["type"]!!.jsonObject["const"]!!.jsonPrimitive.content
        type to body["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      }
    val mutationVariants = DesignMutationV1.serializer().descriptor.getElementDescriptor(1)
    val serializerVariants =
      mutationVariants.elementNames.associateWith { type ->
        val descriptor = mutationVariants.getElementDescriptor(mutationVariants.elementIndex(type))
        descriptor.requiredFields() + "type"
      }

    assertEquals(serializerVariants, schemaVariants)
  }

  @Test
  fun `document schema required fields match the protocol serializer`() {
    val documentSchema = schema("schemas/compose-ui-builder-document-v1.schema.json")
    val required = documentSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    assertEquals(DesignDocumentV1.serializer().descriptor.requiredFields(), required)
  }

  @Test
  fun `document schema validates every committed fixture including home`() {
    val schemaText = schemaText("schemas/compose-ui-builder-document-v1.schema.json")
    val validator =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(schemaText, InputFormat.JSON)
    val fixtureDirectory =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "docs/design/fixtures/ui-builder/designs") }
        .first(File::isDirectory)

    val documents =
      fixtureDirectory
        .listFiles { file -> file.extension == "json" }!!
        .map { fixture ->
          fixture.name to
            UiBuilderReducer.replay(json.parseToJsonElement(fixture.readText()).jsonObject)
              .document
              .toDesignDocumentV1()
        }
    val withHome =
      documents
        .first()
        .second
        .copy(home = DesignHomeV1.Server("https://preview.coo.ee", documents.first().second.id))

    (documents + ("document-with-home.json" to withHome)).forEach { (name, document) ->
      val errors = validator.validate(json.encodeToString(document), InputFormat.JSON)
      assertTrue(errors.isEmpty(), "$name: ${errors.joinToString()}")
    }
  }

  private fun SerialDescriptor.requiredFields(): Set<String> =
    elementNames.filterIndexed { index, _ -> !isElementOptional(index) }.toSet()

  private fun SerialDescriptor.elementIndex(name: String): Int =
    (0 until elementsCount).single { getElementName(it) == name }

  private fun schema(path: String) = json.parseToJsonElement(schemaText(path)).jsonObject

  private fun schemaText(path: String) =
    requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
      .bufferedReader()
      .readText()
}
