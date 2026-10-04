package ee.schimke.composeai.uibuilder.export.production

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val stringType = ProductionType.Scalar(ScalarType.STRING)

internal fun productionFixtureInputs(): List<ProductionInput> {
  val models =
    ProductionUidFile(
      schema = ProductionUidFiles.SCHEMA,
      models =
        listOf(
          ProductionModel(
            "library",
            "example.ui.LibraryUiData",
            ModelOwnership.GENERATED,
            listOf(
              ProductionField("title", stringType),
              ProductionField("episode", ProductionType.Model("episode")),
              ProductionField("episodes", ProductionType.ListType(ProductionType.Model("episode"))),
              ProductionField("featured", ProductionType.Model("external")),
              ProductionField(
                "subtitle",
                ProductionType.Scalar(ScalarType.STRING, nullable = true),
              ),
            ),
          ),
          ProductionModel(
            "episode",
            "example.ui.EpisodeUiData",
            ModelOwnership.GENERATED,
            listOf(ProductionField("id", stringType), ProductionField("title", stringType)),
          ),
          ProductionModel(
            "external",
            "example.domain.ProjectEpisode",
            ModelOwnership.EXTERNAL,
            listOf(ProductionField("title", stringType, property = "displayTitle")),
          ),
        ),
    )
  val component =
    ProductionUidFile(
      schema = ProductionUidFiles.SCHEMA,
      imports = listOf("models/LibraryModels.uid"),
      entryPoint =
        ProductionEntryPoint(
          "episode-card",
          "example.ui.components.EpisodeCard",
          EntryPointKind.COMPONENT,
          ProductionVisibility.INTERNAL,
          "episode",
          "card",
          events = listOf(ProductionEvent("onClick", stringType)),
          bindings = listOf(ProductionBinding("card", "text", listOf("title"), stringType)),
        ),
      design = productionDesign("card"),
    )
  val screen =
    ProductionUidFile(
      schema = ProductionUidFiles.SCHEMA,
      imports = listOf("components/EpisodeCard.uid"),
      entryPoint =
        ProductionEntryPoint(
          "library-screen",
          "example.ui.LibraryScreen",
          EntryPointKind.SCREEN,
          ProductionVisibility.PUBLIC,
          "library",
          "library",
          events = listOf(ProductionEvent("onEpisodeClick", stringType)),
          bindings =
            listOf(ProductionBinding("library", "text", listOf("episode", "title"), stringType)),
          components =
            listOf(
              ProductionComponentUse(
                "library",
                "episode-card",
                listOf("episode"),
                mapOf("onClick" to "onEpisodeClick"),
              )
            ),
        ),
      design = productionDesign("library"),
    )
  return listOf(
    ProductionInput("Library.uid", screen),
    ProductionInput("components/EpisodeCard.uid", component),
    ProductionInput("models/LibraryModels.uid", models),
  )
}

internal fun productionDesign(root: String) =
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1",
    id = root,
    title = root,
    revision = 0,
    catalogPin = JsonObject(emptyMap()),
    environment = JsonObject(emptyMap()),
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(root),
    nodes = mapOf(root to UiBuilderNode(root, "m3/text")),
  )

class ProductionContractsTest {
  private fun validate(inputs: List<ProductionInput>) = ProductionContractValidator.validate(inputs)

  private fun valid(inputs: List<ProductionInput>) =
    assertIs<ProductionContractResult.Valid>(validate(inputs)).contract

  private fun issue(inputs: List<ProductionInput>, code: String): ProductionContractIssue =
    assertIs<ProductionContractResult.Invalid>(validate(inputs)).issues.firstOrNull {
      it.code == code
    } ?: error("no $code in ${validate(inputs)}")

  private fun editScreen(change: (ProductionEntryPoint) -> ProductionEntryPoint) =
    productionFixtureInputs().map { input ->
      if (input.path == "Library.uid")
        input.copy(file = input.file.copy(entryPoint = change(input.file.entryPoint!!)))
      else input
    }

  @Test
  fun `typed cross file contracts round trip without inferring their API`() {
    val inputs = productionFixtureInputs()
    inputs.forEach { input ->
      val encoded = ProductionUidFiles.encode(input.file)
      assertEquals(input.file, ProductionUidFiles.decode(encoded))
      assertEquals(encoded, ProductionUidFiles.encode(ProductionUidFiles.decode(encoded)))
    }
    assertEquals(setOf("library", "episode", "external"), valid(inputs).models.keys)
    assertEquals("displayTitle", valid(inputs).models.getValue("external").fields.single().property)
  }

  @Test
  fun `unknown versions fields and type variants are refused`() {
    val file = ProductionUidFiles.encode(productionFixtureInputs().last().file)
    assertFailsWith<IllegalArgumentException> {
      ProductionUidFiles.decode(
        file.replace(ProductionUidFiles.SCHEMA, "compose-ui-builder-production/v2")
      )
    }
    assertFailsWith<SerializationException> {
      ProductionUidFiles.decode(file.replace("\"models\":", "\"futureApi\": true, \"models\":"))
    }
    assertFailsWith<SerializationException> {
      ProductionUidFiles.decode(file.replace("\"kind\": \"scalar\"", "\"kind\": \"expression\""))
    }
    assertFailsWith<IllegalArgumentException> {
      ProductionUidFiles.decode("{\"schema\":\"compose-ui-builder-document/v1\"}")
    }
  }

  @Test
  fun `generated files are deterministic and external models are not copied`() {
    val inputs = productionFixtureInputs()
    val generated = ProductionModelGenerator.generate(valid(inputs))
    assertEquals(
      listOf("example/ui/EpisodeUiData.kt", "example/ui/LibraryUiData.kt"),
      generated.map { it.path },
    )
    val reordered =
      inputs.reversed().map { it.copy(file = it.file.copy(models = it.file.models.reversed())) }
    assertEquals(generated, ProductionModelGenerator.generate(valid(reordered)))
    val source = generated.last().source
    assertContains(source, "public val featured: example.domain.ProjectEpisode,")
    assertContains(
      source,
      "public val episodes: kotlin.collections.List<example.ui.EpisodeUiData>,",
    )
    assertContains(source, "public val subtitle: kotlin.String?,")
    assertFalse(source.contains("@Immutable"))
    assertFalse(source.contains("= null"))
  }

  @Test
  fun `layout changes and removal of a binding preserve declared model API`() {
    val inputs = productionFixtureInputs()
    val before = ProductionModelGenerator.generate(valid(inputs))
    val changed = inputs.map { input ->
      if (input.path != "Library.uid") input
      else
        input.copy(
          file =
            input.file.copy(
              entryPoint = input.file.entryPoint!!.copy(bindings = emptyList()),
              design = input.file.design!!.copy(title = "A different layout", revision = 17),
            )
        )
    }
    assertEquals(before, ProductionModelGenerator.generate(valid(changed)))
    assertContains(before.last().source, "subtitle")
  }

  @Test
  fun `missing and mistyped fields produce located diagnostics`() {
    val unknown =
      issue(
        editScreen {
          it.copy(
            bindings =
              listOf(ProductionBinding("library", "text", listOf("episode", "missing"), stringType))
          )
        },
        "UNKNOWN_DATA_FIELD",
      )
    assertEquals("Library.uid", unknown.file)
    assertEquals("library-screen", unknown.declaration)
    assertEquals("library", unknown.nodeId)
    assertEquals("text", unknown.field)
    issue(
      editScreen {
        it.copy(
          bindings =
            listOf(
              ProductionBinding(
                "library",
                "text",
                listOf("title"),
                ProductionType.Scalar(ScalarType.BOOLEAN),
              )
            )
        )
      },
      "BINDING_TYPE_MISMATCH",
    )
    issue(
      editScreen {
        it.copy(
          bindings =
            listOf(ProductionBinding("library", "text", listOf("title", "length"), stringType))
        )
      },
      "INVALID_DATA_PATH",
    )
  }

  @Test
  fun `nullable objects cannot be dereferenced or silently assigned to nonnullable values`() {
    val inputs =
      productionFixtureInputs().map { input ->
        input.copy(
          file =
            input.file.copy(
              models =
                input.file.models.map { model ->
                  if (model.id != "library") model
                  else
                    model.copy(
                      fields =
                        model.fields.map { field ->
                          if (field.name != "episode") field
                          else field.copy(type = ProductionType.Model("episode", nullable = true))
                        }
                    )
                }
            )
        )
      }
    issue(inputs, "NULLABLE_PATH")
    issue(
      editScreen {
        it.copy(
          bindings = listOf(ProductionBinding("library", "text", listOf("subtitle"), stringType))
        )
      },
      "BINDING_TYPE_MISMATCH",
    )
  }

  @Test
  fun `callbacks must be explicitly forwarded with identical payload types`() {
    issue(
      editScreen {
        it.copy(components = it.components.map { use -> use.copy(events = emptyMap()) })
      },
      "COMPONENT_EVENT_MISMATCH",
    )
    issue(
      editScreen {
        it.copy(
          events = listOf(ProductionEvent("onEpisodeClick", ProductionType.Scalar(ScalarType.INT)))
        )
      },
      "COMPONENT_EVENT_MISMATCH",
    )
    issue(
      editScreen { it.copy(events = listOf(ProductionEvent("modifier"))) },
      "INVALID_EVENT_NAME",
    )
    issue(
      editScreen {
        it.copy(components = it.components.map { use -> use.copy(dataPath = emptyList()) })
      },
      "COMPONENT_INPUT_MISMATCH",
    )
  }

  @Test
  fun `duplicate owners symbols and fields cannot generate competing files`() {
    val inputs = productionFixtureInputs()
    val models = inputs.last()
    issue(inputs + models.copy(path = "Another.uid"), "DUPLICATE_MODEL")
    issue(
      inputs +
        models.copy(
          path = "Another.uid",
          file = models.file.copy(models = listOf(models.file.models.first().copy(id = "other"))),
        ),
      "DUPLICATE_KOTLIN_SYMBOL",
    )
    issue(
      inputs.map { input ->
        if (input != models) input
        else
          input.copy(
            file =
              input.file.copy(
                models = input.file.models.map { it.copy(fields = it.fields + it.fields.first()) }
              )
          )
      },
      "DUPLICATE_FIELD",
    )
    issue(inputs + inputs.first(), "DUPLICATE_FILE")
    issue(
      inputs +
        models.copy(
          path = "CaseCollision.uid",
          file =
            models.file.copy(
              models =
                listOf(
                  models.file.models
                    .first()
                    .copy(id = "case", kotlinType = "example.ui.libraryuidata")
                )
            ),
        ),
      "OUTPUT_PATH_COLLISION",
    )
  }

  @Test
  fun `invalid Kotlin names and expression shaped property mappings are refused`() {
    val inputs = productionFixtureInputs()
    fun changed(model: (ProductionModel) -> ProductionModel) = inputs.map {
      it.copy(file = it.file.copy(models = it.file.models.map(model)))
    }
    issue(changed { it.copy(kotlinType = "example.ui.class") }, "INVALID_KOTLIN_NAME")
    issue(
      changed { it.copy(fields = it.fields.map { f -> f.copy(name = "val") }) },
      "INVALID_FIELD_NAME",
    )
    issue(
      changed {
        if (it.ownership == ModelOwnership.EXTERNAL)
          it.copy(fields = it.fields.map { f -> f.copy(property = "title.uppercase()") })
        else it
      },
      "INVALID_PROPERTY_MAPPING",
    )
    issue(
      changed {
        if (it.ownership == ModelOwnership.GENERATED) it.copy(fields = emptyList()) else it
      },
      "EMPTY_DATA_CLASS",
    )
    assertTrue(isProductionIdentifier("subtitle"))
    assertFalse(isProductionIdentifier("__"))
  }

  @Test
  fun `recursive models and components are refused`() {
    val inputs = productionFixtureInputs()
    issue(
      inputs.map {
        it.copy(
          file =
            it.file.copy(
              models =
                it.file.models.map { model ->
                  if (model.id != "episode") model
                  else
                    model.copy(
                      fields =
                        model.fields +
                          ProductionField(
                            "parent",
                            ProductionType.ListType(ProductionType.Model("library")),
                          )
                    )
                }
            )
        )
      },
      "MODEL_CYCLE",
    )
    issue(
      inputs.map { input ->
        if (input.path != "components/EpisodeCard.uid") input
        else
          input.copy(
            file =
              input.file.copy(
                entryPoint =
                  input.file.entryPoint!!.copy(
                    components =
                      listOf(
                        ProductionComponentUse(
                          "card",
                          "episode-card",
                          emptyList(),
                          mapOf("onClick" to "onClick"),
                        )
                      )
                  )
              )
          )
      },
      "COMPONENT_CYCLE",
    )
  }

  @Test
  fun `preview application state cannot pass the production contract gate`() {
    val inputs =
      productionFixtureInputs().map { input ->
        if (input.path != "Library.uid") input
        else
          input.copy(
            file =
              input.file.copy(
                design =
                  input.file.design!!.copy(
                    stateVariables = JsonObject(mapOf("selected" to JsonPrimitive(true)))
                  )
              )
          )
      }
    issue(inputs, "OWNED_APPLICATION_STATE")
    issue(editScreen { it.copy(root = "missing") }, "INVALID_ROOT")
    issue(
      editScreen {
        it.copy(
          bindings = listOf(ProductionBinding("missing", "text", listOf("title"), stringType))
        )
      },
      "UNKNOWN_NODE",
    )
  }

  @Test
  fun `declarations in unrelated inputs are not implicit imports`() {
    val inputs =
      productionFixtureInputs().map { input ->
        if (input.path != "Library.uid") input
        else input.copy(file = input.file.copy(imports = emptyList()))
      }
    issue(inputs, "MODEL_NOT_IMPORTED")
    issue(inputs, "COMPONENT_NOT_IMPORTED")
    issue(productionFixtureInputs().dropLast(1), "MISSING_IMPORT")
    issue(
      productionFixtureInputs().map { it.copy(path = "https://host/${it.path}") },
      "INVALID_FILE_PATH",
    )
  }
}
