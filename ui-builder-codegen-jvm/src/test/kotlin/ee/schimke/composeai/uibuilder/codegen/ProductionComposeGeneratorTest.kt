package ee.schimke.composeai.uibuilder.codegen

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.production.*
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class ProductionComposeGeneratorTest {
  private val consumer = Path.of(System.getProperty("consumerProject"))
  private val records =
    listOf("compose-foundation-components-v1.json", "m3-catalog-components-v1.json").map {
      Path.of(System.getProperty("fixtureRecords")).resolve(it)
    }
  private val digest = ProductionGenerationCli.recordDigest(records)
  private val inputs =
    listOf("Library.uid", "Queue.uid", "components/EpisodeCard.uid", "models/LibraryModels.uid")
      .map { name ->
        val relative = "src/main/ui/$name"
        ProductionInput(
          relative,
          ProductionUidFiles.decode(Files.readString(consumer.resolve(relative))),
        )
      }
  private val record = Json {
    ignoreUnknownKeys = true
  }
    .let { json ->
      val decoded = records.map {
        json.decodeFromString(ComponentRecordFile.serializer(), Files.readString(it))
      }
      decoded.first().newBuilder().apply { components = decoded.flatMap { it.components } }.build()
    }

  private fun generate(inputs: List<ProductionInput> = this.inputs, digest: String = this.digest) =
    ProductionComposeGenerator.generate(
      assertIs<ProductionContractResult.Valid>(ProductionContractValidator.validate(inputs))
        .contract,
      record,
      digest,
    )

  private fun editScreen(change: (ProductionUidFile) -> ProductionUidFile) = inputs.map {
    if (it.path.endsWith("/Library.uid")) it.copy(file = change(it.file)) else it
  }

  @Test
  fun `shared component body lives once in its own file with stable public APIs`() {
    val files = generate()
    assertEquals(
      listOf(
        "example/ui/LibraryData.kt",
        "example/ui/LibraryScreen.kt",
        "example/ui/QueueScreen.kt",
        "example/ui/components/EpisodeCard.kt",
      ),
      files.map { it.path },
    )
    val screen = files.single { it.path.endsWith("LibraryScreen.kt") }.source
    val component = files.single { it.path.endsWith("EpisodeCard.kt") }.source
    assertContains(screen, "data: example.ui.LibraryData")
    assertContains(screen, "data.featured.displayTitle")
    assertContains(screen, "import example.ui.components.UidBody")
    assertFalse(screen.contains("fun EpisodeCard"))
    assertContains(component, "internal fun EpisodeCard(")
    assertFalse(files.any { it.path.endsWith("ProjectEpisode.kt") })
    assertContains(files.first().source, "List<example.domain.ProjectEpisode>")
    assertEquals(files, generate(inputs.reversed()))
    val fewerReads =
      generate(
        editScreen { file ->
          file.copy(
            entryPoint = file.entryPoint!!.copy(bindings = emptyList()),
            design =
              file.design!!.copy(
                nodes =
                  file.design!!.nodes +
                    ("title" to
                      file.design!!
                        .nodes
                        .getValue("title")
                        .copy(
                          properties =
                            buildJsonObject {
                              putJsonObject("text") {
                                put("type", "string")
                                put("value", "Static title")
                              }
                            }
                        ))
              ),
          )
        }
      )
    assertContains(
      fewerReads.single { it.path.endsWith("LibraryScreen.kt") }.source,
      "data: example.ui.LibraryData",
    )
    assertEquals(files.first(), fewerReads.first())
  }

  @Test
  fun `catalog drift and incomplete interactive output fail explicitly`() {
    assertContains(
      assertFailsWith<IllegalArgumentException> { generate(digest = "different") }.message!!,
      "catalogDigest",
    )
    assertContains(
      assertFailsWith<IllegalArgumentException> {
          generate(
            editScreen {
              it.copy(
                entryPoint =
                  it.entryPoint!!.copy(
                    bindings =
                      listOf(it.entryPoint!!.bindings.single().copy(property = "unsupported"))
                  )
              )
            }
          )
        }
        .message!!,
      "binding",
    )
  }

  @Test
  fun `callbacks are required API parameters and mapped payloads are forwarded`() {
    val files = generate()
    val screen = files.single { it.path.endsWith("LibraryScreen.kt") }.source
    val component = files.single { it.path.endsWith("EpisodeCard.kt") }.source
    assertContains(screen, "onEpisodeClick: (kotlin.String) -> kotlin.Unit,")
    assertContains(screen, "c0 = { onEpisodeClick(data.featured.displayTitle) }")
    assertContains(component, "onClick = c0")
    assertContains(component, "c0 = { onEpisodeClick(data.displayTitle) }")
    assertFalse(files.any { "remember" in it.source || "TODO" in it.source })
    // Removing a visual binding cannot remove the declared callback from the application API.
    val unbound =
      generate(
        editScreen { file ->
          file.copy(
            entryPoint =
              file.entryPoint!!.copy(
                events = file.entryPoint!!.events + ProductionEvent("onRefresh")
              )
          )
        }
      )
    assertContains(
      unbound.single { it.path.endsWith("LibraryScreen.kt") }.source,
      "onRefresh: () -> kotlin.Unit,",
    )
  }

  @Test
  fun `payload-free callbacks and complete model payloads retain their declared types`() {
    data class Case(
      val payload: ProductionType?,
      val path: List<String>?,
      val signature: String,
      val call: String,
    )
    for ((payload, path, signature, call) in
      listOf(
        Case(null, null, "()", "onEpisodeClick()"),
        Case(
          ProductionType.Model("external"),
          emptyList(),
          "(example.domain.ProjectEpisode)",
          "onEpisodeClick(data.featured)",
        ),
      )) {
      val changed = inputs.map { input ->
        val entry = input.file.entryPoint
        if (entry == null) input
        else
          input.copy(
            file =
              input.file.copy(
                entryPoint =
                  entry.copy(
                    events = listOf(ProductionEvent("onEpisodeClick", payload)),
                    eventBindings = entry.eventBindings.map { it.copy(payloadPath = path) },
                  )
              )
          )
      }
      val files = generate(changed)
      val screen = files.single { it.path.endsWith("LibraryScreen.kt") }.source
      assertContains(screen, "onEpisodeClick: $signature -> kotlin.Unit,")
      assertContains(screen, call)
      changed.forEach {
        assertEquals(it.file, ProductionUidFiles.decode(ProductionUidFiles.encode(it.file)))
      }
    }
  }

  @Test
  fun `invalid event contracts refuse before generation`() {
    fun checkIssue(code: String, change: (ProductionEntryPoint) -> ProductionEntryPoint) {
      val changed = inputs.map { input ->
        if (input.path.endsWith("EpisodeCard.uid"))
          input.copy(file = input.file.copy(entryPoint = change(input.file.entryPoint!!)))
        else input
      }
      val result =
        assertIs<ProductionContractResult.Invalid>(ProductionContractValidator.validate(changed))
      assertTrue(result.issues.any { it.code == code }, result.issues.toString())
    }
    checkIssue("UNKNOWN_EVENT") {
      it.copy(eventBindings = listOf(it.eventBindings.single().copy(event = "missing")))
    }
    checkIssue("EVENT_PAYLOAD_REQUIRED") {
      it.copy(eventBindings = listOf(it.eventBindings.single().copy(payloadPath = null)))
    }
    checkIssue("EVENT_PAYLOAD_MISMATCH") {
      it.copy(eventBindings = listOf(it.eventBindings.single().copy(payloadPath = emptyList())))
    }
    checkIssue("DUPLICATE_BINDING") { it.copy(eventBindings = it.eventBindings + it.eventBindings) }
    val missingForward = editScreen { file ->
      file.copy(
        entryPoint =
          file.entryPoint!!.copy(
            components = file.entryPoint!!.components.map { it.copy(events = emptyMap()) }
          )
      )
    }
    val result =
      assertIs<ProductionContractResult.Invalid>(
        ProductionContractValidator.validate(missingForward)
      )
    assertTrue(result.issues.any { it.code == "COMPONENT_EVENT_MISMATCH" })
  }

  @Test
  fun `a callback cannot target a value or composable slot`() {
    for (property in listOf("enabled", "content", "missing")) {
      val changed = inputs.map { input ->
        if (input.path.endsWith("EpisodeCard.uid")) {
          val entry = input.file.entryPoint!!
          input.copy(
            file =
              input.file.copy(
                entryPoint =
                  entry.copy(
                    eventBindings = listOf(entry.eventBindings.single().copy(property = property))
                  )
              )
          )
        } else input
      }
      assertFailsWith<IllegalArgumentException>(property) { generate(changed) }
    }
  }

  @Test
  fun `design bindings that are not declared reads are refused`() {
    val collision = editScreen { file ->
      val design = file.design!!
      val root = design.nodes.getValue("root")
      file.copy(
        design =
          design.copy(
            nodes =
              design.nodes +
                ("root" to
                  root.copy(
                    slots =
                      root.slots + ("children" to root.slots.getValue("children") + "subtitle")
                  )) +
                ("subtitle" to
                  design.nodes
                    .getValue("title")
                    .copy(
                      id = "subtitle",
                      properties =
                        buildJsonObject {
                          putJsonObject("text") {
                            put("type", "binding")
                            put("value", "p0")
                          }
                        },
                    ))
          )
      )
    }
    assertContains(
      assertFailsWith<IllegalArgumentException> { generate(collision) }.message!!,
      "subtitle.text: design binding `p0` is not a declared entry-point binding",
    )
  }

  @Test
  fun `ordinary export remains one file without production data parameters`() {
    val design = inputs.single { it.path.endsWith("EpisodeCard.uid") }.file.design!!
    val literal =
      design.copy(
        roots = listOf("root"),
        nodes =
          emptyMap<String, ee.schimke.composeai.uibuilder.export.UiBuilderNode>() +
            ("root" to
              design.nodes
                .getValue("root")
                .copy(
                  properties =
                    buildJsonObject {
                      putJsonObject("text") {
                        put("type", "string")
                        put("value", "Ordinary export")
                      }
                    }
                )),
      )
    val emitted =
      assertIs<ScreenExportGate.Outcome.Emitted>(
          ScreenExportGate.export(literal.toDesignDocumentV1(), record)
        )
        .source
    assertContains(emitted, "Ordinary export")
    assertFalse(emitted.contains("data: example"))
    assertFalse(emitted.contains("UidBody"))
    val bound =
      literal.copy(
        nodes =
          literal.nodes +
            ("root" to
              literal.nodes
                .getValue("root")
                .copy(
                  properties =
                    buildJsonObject {
                      putJsonObject("text") {
                        put("type", "binding")
                        put("value", "p0")
                      }
                    }
                ))
      )
    val refused =
      assertIs<ScreenExportGate.Outcome.Refused>(
        ScreenExportGate.export(bound.toDesignDocumentV1(), record)
      )
    assertTrue(refused.reasons.any { "outside a component or loop" in it })
  }

  private fun dynamicInputs(): List<ProductionInput> =
    inputs +
      listOf("DynamicLibrary.uid", "models/DynamicModels.uid").map { name ->
        val path = "src/main/ui/$name"
        ProductionInput(path, ProductionUidFiles.decode(Files.readString(consumer.resolve(path))))
      }

  @Test
  fun `nullable and keyed placements use declared scopes and deterministic stateless wrappers`() {
    val inputs = dynamicInputs()
    val files = generate(inputs)
    assertEquals(files, generate(inputs.reversed()))
    val source = files.single { it.path.endsWith("DynamicLibraryScreen.kt") }.source
    assertContains(source, "data.selection?.title ?: \"No selection\"")
    assertContains(source, "data.selection?.featured")
    assertContains(source, "uidComposeKey(uidKeys1[uidIndex1])")
    assertContains(source, "data = uidItem1")
    assertContains(source, "Duplicate production list keys")
    assertContains(source, "@androidx.compose.runtime.Composable () -> kotlin.Unit")
    assertFalse(source.contains("remember"))
    assertFalse(source.contains("Placement0"))
    inputs.forEach {
      assertEquals(it.file, ProductionUidFiles.decode(ProductionUidFiles.encode(it.file)))
    }
  }

  @Test
  fun `missing fallbacks keys branches and invalid item paths refuse before generation`() {
    fun check(code: String, change: (ProductionEntryPoint) -> ProductionEntryPoint) {
      val inputs =
        dynamicInputs().map { input ->
          if (input.path.endsWith("/DynamicLibrary.uid"))
            input.copy(file = input.file.copy(entryPoint = change(input.file.entryPoint!!)))
          else input
        }
      val invalid =
        assertIs<ProductionContractResult.Invalid>(ProductionContractValidator.validate(inputs))
      assertTrue(invalid.issues.any { it.code == code }, invalid.issues.toString())
    }
    check("MISSING_NULL_FALLBACK") {
      it.copy(bindings = it.bindings.map { b -> b.copy(fallback = null) })
    }
    check("INVALID_NULL_FALLBACK") {
      it.copy(bindings = it.bindings.map { b -> b.copy(fallback = JsonPrimitive(42)) })
    }
    check("MISSING_NULL_BRANCH") {
      it.copy(components = it.components.map { c -> c.copy(onNull = null) })
    }
    check("MISSING_LIST_KEY") {
      it.copy(components = it.components.map { c -> c.copy(keyPath = null) })
    }
    check("INVALID_LIST_KEY") {
      it.copy(
        components =
          it.components.map { c -> if (c.keyPath != null) c.copy(keyPath = emptyList()) else c }
      )
    }
    check("UNKNOWN_DATA_FIELD") {
      it.copy(
        components =
          it.components.map { c ->
            if (c.keyPath != null) c.copy(keyPath = listOf("missing")) else c
          }
      )
    }
    check("INVALID_DATA_PATH") {
      it.copy(
        components =
          it.components.map { c ->
            if (c.keyPath != null) c.copy(keyPath = listOf("id", "missing")) else c
          }
      )
    }
    check("UNKNOWN_DATA_FIELD") {
      it.copy(bindings = it.bindings.map { b -> b.copy(path = listOf("selection", "missing")) })
    }
  }

  @Test
  fun `fallback literals are typed and nullable items are refused`() {
    fun scalarInputs(type: ScalarType, fallback: JsonPrimitive) =
      dynamicInputs().map { input ->
        input.copy(
          file =
            input.file.copy(
              entryPoint =
                input.file.entryPoint?.let { entry ->
                  if (entry.id != "dynamic-library") entry
                  else
                    entry.copy(
                      bindings =
                        entry.bindings.map {
                          it.copy(expectedType = ProductionType.Scalar(type), fallback = fallback)
                        }
                    )
                },
              models =
                input.file.models.map { model ->
                  if (model.id != "selection") model
                  else
                    model.copy(
                      fields =
                        model.fields.map { field ->
                          if (field.name != "title") field
                          else field.copy(type = ProductionType.Scalar(type, nullable = true))
                        }
                    )
                },
            )
        )
      }
    for ((type, value) in
      listOf(
        ScalarType.BOOLEAN to JsonPrimitive(false),
        ScalarType.INT to JsonPrimitive(Int.MIN_VALUE),
        ScalarType.LONG to JsonPrimitive(Long.MIN_VALUE),
        ScalarType.FLOAT to JsonPrimitive(0.5f),
        ScalarType.DOUBLE to JsonPrimitive(0.5),
        ScalarType.STRING to JsonPrimitive("literal"),
      )) {
      assertIs<ProductionContractResult.Valid>(
        ProductionContractValidator.validate(scalarInputs(type, value))
      )
    }
    for ((type, value) in
      listOf(
        ScalarType.INT to JsonPrimitive(2147483648L),
        ScalarType.LONG to JsonPrimitive(1.5),
        ScalarType.FLOAT to JsonPrimitive(Double.MAX_VALUE),
        ScalarType.BOOLEAN to JsonPrimitive("true"),
        ScalarType.STRING to JsonPrimitive(1),
      )) {
      val invalid =
        assertIs<ProductionContractResult.Invalid>(
          ProductionContractValidator.validate(scalarInputs(type, value))
        )
      assertTrue(invalid.issues.any { it.code == "INVALID_NULL_FALLBACK" })
    }
    val nullableItems =
      dynamicInputs().map { input ->
        input.copy(
          file =
            input.file.copy(
              models =
                input.file.models.map { model ->
                  if (model.id != "dynamic-library") model
                  else
                    model.copy(
                      fields =
                        model.fields.map { field ->
                          if (field.name != "episodes") field
                          else
                            field.copy(
                              type =
                                ProductionType.ListType(
                                  ProductionType.Model("external", nullable = true)
                                )
                            )
                        }
                    )
                }
            )
        )
      }
    val invalid =
      assertIs<ProductionContractResult.Invalid>(
        ProductionContractValidator.validate(nullableItems)
      )
    assertTrue(invalid.issues.any { it.code == "INVALID_LIST_KEY" })
    assertTrue(invalid.issues.any { it.code == "COMPONENT_INPUT_MISMATCH" })
  }

  @Test
  fun `output replacement removes stale files and protects handwritten sources`() {
    val root = Files.createTempDirectory("uid-output-test").toRealPath()
    try {
      val output = root.resolve("build/generated/uiBuilder")
      ProductionGenerationCli.write(root, output, listOf(ProductionGeneratedFile("Old.kt", "old")))
      ProductionGenerationCli.write(root, output, listOf(ProductionGeneratedFile("New.kt", "new")))
      assertFalse(Files.exists(output.resolve("Old.kt")))
      assertEquals("new", Files.readString(output.resolve("New.kt")))
      assertFailsWith<IllegalArgumentException> {
        ProductionGenerationCli.write(root, root.resolve("src/main/kotlin"), emptyList())
      }
      val unowned = root.resolve("build/handwritten")
      Files.createDirectories(unowned)
      Files.writeString(unowned.resolve("Keep.kt"), "keep")
      assertFailsWith<IllegalArgumentException> {
        ProductionGenerationCli.write(root, unowned, emptyList())
      }
      assertEquals("keep", Files.readString(unowned.resolve("Keep.kt")))
      val linked = root.resolve("build/link")
      Files.createSymbolicLink(linked, unowned)
      assertFailsWith<IllegalArgumentException> {
        ProductionGenerationCli.write(root, linked.resolve("generated"), emptyList())
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
