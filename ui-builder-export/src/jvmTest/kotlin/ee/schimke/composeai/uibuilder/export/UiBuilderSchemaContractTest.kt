package ee.schimke.composeai.uibuilder.export

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderSchemaContractTest {
  private val json = Json { explicitNulls = false }

  /**
   * The schemas this repository publishes are the protocol's own, generated from its serializers,
   * plus the stable `$id` a release URL is cited by — not a hand-kept copy checked after the fact.
   */
  @Test
  fun `the published schemas are the protocol's generated ones under this repository's ids`() {
    mapOf(
        "compose-ui-builder-document-v1.schema.json" to
          ("design-document-v1.schema.json" to
            "https://schemas.compose-preview.dev/ui-builder/document/v1"),
        "compose-ui-builder-mutation-v1.schema.json" to
          ("design-mutation-v1.schema.json" to
            "https://schemas.compose-preview.dev/ui-builder/mutation/v1"),
      )
      .forEach { (published, source) ->
        val (generated, id) = source
        val bundled = schema("schemas/$published")
        assertEquals(id, bundled["\$id"]?.jsonPrimitive?.content, published)
        assertEquals(schema("schemas/$generated"), JsonObject(bundled - "\$id"), published)
      }
  }

  @Test
  fun `the mutation schema names every mutation the protocol serializer has`() {
    val defs = schema("schemas/compose-ui-builder-mutation-v1.schema.json")["\$defs"]!!.jsonObject
    val mutationVariants = DesignMutationV1.serializer().descriptor.getElementDescriptor(1)
    mutationVariants.elementNames.forEach { type ->
      assertTrue(defs.keys.any { it.endsWith(".$type") }, "no schema for mutation $type")
    }
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

  private fun schema(path: String) = json.parseToJsonElement(schemaText(path)).jsonObject

  private fun schemaText(path: String) =
    requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
      .bufferedReader()
      .readText()
}
