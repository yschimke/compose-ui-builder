package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.export.VariableFontText.Font
import ee.schimke.composeai.uibuilder.export.VariableFontText.Spec
import ee.schimke.composeai.uibuilder.export.VariableFontText.Target
import ee.schimke.flexpress.VariableFont
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * flexpress's code generator, run over the vendored variable fonts the way an export runs it, and
 * its standalone Compose UI output put through a real Kotlin compiler against Compose Desktop.
 */
class FlexpressVariableFontSourcesTest {
  private val fonts =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "assets/rc-fonts") }
      .first { File(it, "fonts.json").isFile }

  private val sources = FlexpressVariableFontSources {
    File(fonts, it).takeIf(File::isFile)?.readBytes()
  }

  private val dir = Files.createTempDirectory("flexpress-sources-")

  @AfterTest fun cleanup() = dir.toFile().deleteRecursively().let {}

  private fun request(
    text: String = "Flex",
    font: Font = Font.RobotoFlex,
    animated: List<String> = listOf("wght"),
    location: Map<String, Float> = emptyMap(),
    target: Target = Target.COMPOSE_UI,
  ) = VariableFontText.requests(listOf(Spec(font, text, animated, location, target))).single()

  private fun generated(
    request: VariableFontText.Request,
    mode: VariableFontExportMode = VariableFontExportMode.STANDALONE,
  ): VariableFontSource.Generated =
    assertIs<VariableFontSource.Generated>(sources.generate(request, PACKAGE, mode))

  @Test
  fun `each font's axes are the file's own`() {
    Font.entries.forEach { font ->
      val axes = VariableFont.parse(File(fonts, font.file).readBytes()).axes.associateBy { it.tag }
      font.axes.forEach { axis ->
        val real = checkNotNull(axes[axis.tag]) { "${font.file} has no ${axis.tag}" }
        assertEquals(
          Triple(real.minValue, real.defaultValue, real.maxValue),
          Triple(axis.min, axis.default, axis.max),
          "${font.family} ${axis.tag}",
        )
      }
    }
  }

  /**
   * The generator as an export host builds it, with nothing but this module on its classpath: every
   * font, and its licence, is in the jar's own resources.
   */
  @Test
  fun `the fonts ship in this module's resources`() {
    Font.entries.forEach { font ->
      val bundled =
        checkNotNull(
          FlexpressVariableFontSources::class
            .java
            .getResourceAsStream(FlexpressVariableFontSources.CLASSPATH_FONTS + font.file)
        ) {
          "${font.file} is not staged"
        }
      assertTrue(bundled.use { it.readBytes() }.contentEquals(File(fonts, font.file).readBytes()))
    }
    listOf("RobotoFlex-OFL.txt", "GoogleSansFlex-OFL.txt").forEach {
      assertTrue(
        FlexpressVariableFontSources::class
          .java
          .getResource(FlexpressVariableFontSources.CLASSPATH_FONTS + it) != null,
        it,
      )
    }
    val generated =
      FlexpressVariableFontSources.fromClasspath()
        .generate(request(font = Font.GoogleSansFlex), PACKAGE, VariableFontExportMode.LIBRARY)
    assertIs<VariableFontSource.Generated>(generated)
  }

  @Test
  fun `the library form draws through flexpress for each target`() {
    val compose = generated(request(), VariableFontExportMode.LIBRARY)
    assertEquals("VariableFontTextRobotoFlexFlexWght.kt", compose.fileName)
    assertTrue("import ee.schimke.flexpress.compose.VariableFontText" in compose.source)
    assertTrue("wght: () -> Float," in compose.source, compose.source)
    assertTrue("// Written by the Compose UI builder's export." in compose.source)

    val remote = generated(request(target = Target.REMOTE), VariableFontExportMode.LIBRARY)
    assertTrue("import ee.schimke.flexpress.RemoteVariableFontText" in remote.source)
    assertTrue("wght: RemoteFloat," in remote.source, remote.source)
  }

  @Test
  fun `the standalone form needs no flexpress`() {
    listOf(Target.COMPOSE_UI, Target.REMOTE).forEach { target ->
      val source = generated(request(target = target)).source
      assertFalse("ee.schimke.flexpress" in source, source)
      assertTrue("private object VariableFontTextRobotoFlexFlexWghtOutline" in source, source)
    }
  }

  @Test
  fun `a held axis is no parameter`() {
    val source =
      generated(request(animated = emptyList(), location = mapOf("wght" to 800f, "wdth" to 75f)))
        .source
    assertTrue(
      "internal fun VariableFontTextRobotoFlexFlex(\n  fontSize: TextUnit," in source,
      source,
    )
  }

  @Test
  fun `text the font has no glyphs for is refused, naming the characters`() {
    val refused =
      assertIs<VariableFontSource.Refused>(
        sources.generate(request("Flex 日本"), PACKAGE, VariableFontExportMode.STANDALONE)
      )
    assertEquals("Roboto Flex has no glyph for 日 本 in \"Flex 日本\"", refused.reason)
  }

  @Test
  fun `a font that cannot be read and an axis the font does not offer are refused`() {
    val none = FlexpressVariableFontSources { null }
    val unread =
      assertIs<VariableFontSource.Refused>(
        none.generate(request(), PACKAGE, VariableFontExportMode.LIBRARY)
      )
    assertEquals("Roboto Flex (RobotoFlex.ttf) could not be read: no such file", unread.reason)
    val axis =
      assertIs<VariableFontSource.Refused>(
        sources.generate(
          request(animated = listOf("ROND")),
          PACKAGE,
          VariableFontExportMode.LIBRARY,
        )
      )
    assertEquals("Roboto Flex has no rond axis a design can set", axis.reason)
  }

  /**
   * The shape an export with one file takes: a screen calling two generated texts, joined into one
   * file, compiles against Compose Desktop's `ui` and nothing of flexpress's.
   */
  @Test
  fun `standalone Compose UI texts joined into a screen compile against Compose Desktop`() {
    val flex = request()
    val sans = request("Hello", Font.GoogleSansFlex, listOf("wght", "ROND"), mapOf("wdth" to 120f))
    val screen =
      """
      |package $PACKAGE
      |
      |import androidx.compose.foundation.layout.Column
      |import androidx.compose.runtime.Composable
      |import androidx.compose.ui.unit.sp
      |
      |@Composable
      |fun Screen(weight: Float) {
      |  Column {
      |    ${flex.functionName}(wght = { weight }, fontSize = 32.sp)
      |    ${sans.functionName}(wght = { weight }, rond = { 100f }, fontSize = 24.sp)
      |  }
      |}
      |"""
        .trimMargin()
    val joined = joinKotlinFiles(screen, listOf(generated(flex).source, generated(sans).source))
    assertEquals(1, Regex("(?m)^package ").findAll(joined).count(), joined)
    val (result, diagnostics) = compile(joined)
    assertEquals(ExitCode.OK, result, "$diagnostics\n$joined")
  }

  private val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI()).path
  private val desktopCompose =
    checkNotNull(System.getProperty("desktopCompose.classpath")) {
      "the build passes CMP desktop's classpath as -DdesktopCompose.classpath"
    }

  private val composePlugin =
    checkNotNull(System.getProperty("composeCompiler.plugin")) {
      "the build passes the Compose compiler plugin as -DcomposeCompiler.plugin"
    }

  private fun compile(source: String): Pair<ExitCode, String> {
    val work = Files.createTempDirectory(dir, "compile-")
    val file = work.resolve("Screen.kt").also { Files.writeString(it, source) }
    val diagnostics = ByteArrayOutputStream()
    val arguments =
      listOf(
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-Xplugin=$composePlugin",
        "-classpath",
        listOf(stdlib, desktopCompose).joinToString(File.pathSeparator),
        "-d",
        work.resolve("classes").toString(),
        file.toString(),
      )
    val result =
      PrintStream(diagnostics).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
    return result to diagnostics.toString()
  }

  private companion object {
    const val PACKAGE = "generated.uibuilder"
  }
}
