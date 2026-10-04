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
      decoded.first().copy(components = decoded.flatMap { it.components })
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
                entryPoint = it.entryPoint!!.copy(events = listOf(ProductionEvent("onClick")))
              )
            }
          )
        }
        .message!!,
      "event lowering",
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
        nodes =
          design.nodes +
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
                ))
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
