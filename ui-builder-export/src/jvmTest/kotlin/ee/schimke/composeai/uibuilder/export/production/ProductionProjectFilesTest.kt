package ee.schimke.composeai.uibuilder.export.production

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

class ProductionProjectFilesTest {
  private val root = Files.createTempDirectory("production-project-")

  @AfterTest
  fun cleanup() {
    root.toFile().deleteRecursively()
  }

  private fun git(vararg args: String) {
    val process =
      ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeInputs(track: Boolean = true) {
    git("init", "-q")
    productionFixtureInputs().forEach { input ->
      val path = root.resolve(input.path)
      Files.createDirectories(path.parent)
      Files.writeString(path, ProductionUidFiles.encode(input.file))
    }
    if (track) git("add", "--", "Library.uid", "components", "models")
  }

  private fun load() = ProductionProjectFiles.load(root, listOf("Library.uid"))

  private fun valid() = assertIs<ProductionContractResult.Valid>(load()).contract

  @Test
  fun `registered tracked inputs resolve only their import closure and accept local edits`() {
    writeInputs()
    Files.writeString(root.resolve("Unregistered.uid"), "a draft that is not a production file")
    val file = root.resolve("Library.uid")
    val edited =
      ProductionUidFiles.decode(Files.readString(file)).let {
        it.copy(design = it.design!!.copy(title = "Local uncommitted edit"))
      }
    Files.writeString(file, ProductionUidFiles.encode(edited))
    val contract = valid()
    assertEquals(3, contract.inputs.size)
    assertEquals(
      "Local uncommitted edit",
      contract.inputs.first { it.path == "Library.uid" }.file.design!!.title,
    )
  }

  @Test
  fun `untracked entry files and imported files refuse generation`() {
    writeInputs(track = false)
    assertContains(
      assertIs<ProductionContractResult.Invalid>(load()).issues.map { it.code },
      "UNTRACKED_INPUT",
    )
    git("add", "--", "Library.uid")
    val issues = assertIs<ProductionContractResult.Invalid>(load()).issues
    assertEquals("components/EpisodeCard.uid", issues.first { it.code == "UNTRACKED_INPUT" }.file)
  }

  @Test
  fun `remote traversal and symlink inputs are refused`() {
    writeInputs()
    listOf(
        "../Library.uid",
        "/Library.uid",
        "https://host/Library.uid",
        "components\\EpisodeCard.uid",
      )
      .forEach { path ->
        assertContains(
          assertIs<ProductionContractResult.Invalid>(
              ProductionProjectFiles.load(root, listOf(path))
            )
            .issues
            .map { it.code },
          "INVALID_FILE_PATH",
        )
      }
    Files.createSymbolicLink(root.resolve("Link.uid"), root.resolve("Library.uid"))
    git("add", "--", "Link.uid")
    assertContains(
      assertIs<ProductionContractResult.Invalid>(
          ProductionProjectFiles.load(root, listOf("Link.uid"))
        )
        .issues
        .map { it.code },
      "INPUT_OUTSIDE_PROJECT",
    )
  }

  @Test
  fun `unknown schema in a tracked file is refused rather than treated as a draft`() {
    writeInputs()
    val path = root.resolve("models/LibraryModels.uid")
    Files.writeString(
      path,
      Files.readString(path).replace(ProductionUidFiles.SCHEMA, "compose-ui-builder-production/v2"),
    )
    val invalid = assertIs<ProductionContractResult.Invalid>(load())
    assertContains(
      invalid.issues.first { it.code == "INVALID_INPUT" }.message,
      "unsupported production schema",
    )
  }

  @Test
  fun `generated models compile with a handwritten consumer and project owned external model`() {
    writeInputs()
    val committedFixture =
      checkNotNull(javaClass.classLoader.getResource("production/library-models.uid")).readText()
    assertEquals(productionFixtureInputs().last().file, ProductionUidFiles.decode(committedFixture))
    Files.writeString(root.resolve("models/LibraryModels.uid"), committedFixture)
    val generated = ProductionModelGenerator.generate(valid())
    val sources = generated.map { source ->
      root.resolve("generated/${source.path}").also {
        Files.createDirectories(it.parent)
        Files.writeString(it, source.source)
      }
    }
    val external = root.resolve("ProjectEpisode.kt")
    Files.writeString(
      external,
      "package example.domain\npublic data class ProjectEpisode(public val displayTitle: String)\n",
    )
    val consumer = root.resolve("Consumer.kt")
    Files.writeString(
      consumer,
      """
      package example.consumer
      import example.ui.EpisodeUiData
      import example.ui.LibraryUiData
      import example.domain.ProjectEpisode

      public fun exerciseModels(): String {
        val episode = EpisodeUiData(id = "1", title = "Generated")
        val data = LibraryUiData(
          title = "Library",
          episode = episode,
          episodes = listOf(episode),
          featured = ProjectEpisode("External"),
          subtitle = null,
        )
        check(data.copy(title = "Renamed").episodes.single() == episode)
        return data.episode.title + "/" + data.featured.displayTitle
      }
      """
        .trimIndent(),
    )
    val output = root.resolve("classes")
    val diagnostics = ByteArrayOutputStream()
    val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
    val arguments =
      listOf("-no-stdlib", "-no-reflect", "-classpath", stdlib, "-d", output.toString()) +
        (sources + listOf(external, consumer)).map(Path::toString)
    val result =
      PrintStream(diagnostics).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
    assertEquals(ExitCode.OK, result, diagnostics.toString())
    URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
      assertEquals(
        "Generated/External",
        loader.loadClass("example.consumer.ConsumerKt").getMethod("exerciseModels").invoke(null),
      )
    }
  }
}
