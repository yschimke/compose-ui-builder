package ee.schimke.composeai.uibuilder.export

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * The Google Fonts declarations the m3 code pane and the Wear screen generator write, put through a
 * real Kotlin compiler and then run.
 *
 * The text assertions elsewhere (`WearLayoutAndColourExportTest`,
 * `CapabilityComposeCodeExporterTest`) read what the file says; only a compiler can say whether it
 * builds. The families are top-level `private val`s that read `GoogleFontsProvider`, and top-level
 * properties in one file initialise in declaration order — so a provider declared below the
 * families is `Variable 'GoogleFontsProvider' must be initialized` to Kotlin 2, and would be a
 * `null` provider at run time to anything that let it through.
 *
 * `ui-text-google-fonts` is an Android AAR, so the compile runs against stand-ins for the five
 * symbols the file names, with the library's own signatures (`GoogleFont(name, bestEffort)`,
 * `GoogleFont.Provider(providerAuthority, providerPackage, certificates)`, `Font(googleFont,
 * fontProvider, weight)`).
 */
class AndroidGoogleFontsCompileTest {
  private val root = Files.createTempDirectory("google-fonts-compile-")

  @AfterTest fun cleanup() = root.toFile().deleteRecursively().let {}

  @Test
  fun `generated Google Fonts families compile and read an initialised provider`() {
    val fonts = AndroidGoogleFonts()
    val typography =
      fonts.typography(
        "base",
        mapOf("displayLarge" to "Michroma", "bodyLarge" to "google:Exo 2"),
        depth = 1,
      )
    // The generator writes the screen first and the declarations after it, as here.
    val generated = buildString {
      appendLine("package generated.uibuilder")
      appendLine()
      fonts.imports.forEach { appendLine("import $it") }
      appendLine()
      appendLine("class Typography(val displayLarge: Style, val bodyLarge: Style) {")
      appendLine(
        "  fun copy(displayLarge: Style = this.displayLarge, bodyLarge: Style = this.bodyLarge) ="
      )
      appendLine("    Typography(displayLarge, bodyLarge)")
      appendLine("}")
      appendLine()
      appendLine("class Style(val fontFamily: FontFamily?) {")
      appendLine("  fun copy(fontFamily: FontFamily?) = Style(fontFamily)")
      appendLine("}")
      appendLine()
      appendLine("fun theme(base: Typography = Typography(Style(null), Style(null))) =")
      appendLine("  Theme(")
      typography.forEach { appendLine(it) }
      appendLine("  )")
      appendLine()
      appendLine("class Theme(val typography: Typography)")
      appendLine()
      appendLine("fun providers(): List<Any?> {")
      appendLine("  val typography = theme().typography")
      appendLine(
        "  return listOf(typography.displayLarge, typography.bodyLarge).flatMap { style ->"
      )
      appendLine(
        "    (style.fontFamily as StubFontFamily).fonts.map { (it as GoogleFontStub).provider }"
      )
      appendLine("  }")
      appendLine("}")
      appendLine()
      fonts.declarations.forEach {
        appendLine(it)
        appendLine()
      }
    }
    // The generated file's own `import`s must resolve to these, not to a name the stand-ins add.
    val stubs =
      """
      package androidx.compose.ui.text.font

      interface Font

      class FontWeight(val weight: Int) {
        companion object {
          val Normal = FontWeight(400)
          val Medium = FontWeight(500)
          val Bold = FontWeight(700)
        }
      }

      abstract class FontFamily

      fun FontFamily(vararg fonts: Font): FontFamily = generated.uibuilder.StubFontFamily(fonts.toList())
      """
        .trimIndent()
    val googleStubs =
      """
      package androidx.compose.ui.text.googlefonts

      import androidx.compose.ui.text.font.FontWeight

      class GoogleFont(val name: String, val bestEffort: Boolean = true) {
        class Provider(
          val providerAuthority: String,
          val providerPackage: String,
          val certificates: List<List<ByteArray>>,
        )
      }

      fun Font(
        googleFont: GoogleFont,
        fontProvider: GoogleFont.Provider,
        weight: FontWeight = FontWeight.Normal,
      ): androidx.compose.ui.text.font.Font =
        generated.uibuilder.GoogleFontStub(googleFont, fontProvider, weight)
      """
        .trimIndent()
    val holders =
      """
      package generated.uibuilder

      import androidx.compose.ui.text.font.Font
      import androidx.compose.ui.text.font.FontFamily
      import androidx.compose.ui.text.font.FontWeight
      import androidx.compose.ui.text.googlefonts.GoogleFont

      class StubFontFamily(val fonts: List<Font>) : FontFamily()

      class GoogleFontStub(
        val font: GoogleFont,
        val provider: GoogleFont.Provider?,
        val weight: FontWeight,
      ) : Font
      """
        .trimIndent()
    val sources =
      mapOf(
          "Generated.kt" to generated,
          "FontStubs.kt" to stubs,
          "GoogleFontStubs.kt" to googleStubs,
          "Holders.kt" to holders,
        )
        .map { (name, text) -> root.resolve(name).also { Files.writeString(it, text) } }

    val output = root.resolve("classes")
    val diagnostics = ByteArrayOutputStream()
    val stdlib = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()
    val arguments =
      listOf("-no-stdlib", "-no-reflect", "-classpath", stdlib, "-d", output.toString()) +
        sources.map(Path::toString)
    val result =
      PrintStream(diagnostics).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
    assertEquals(ExitCode.OK, result, "$diagnostics\n$generated")

    URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
      @Suppress("UNCHECKED_CAST")
      val providers =
        loader.loadClass("generated.uibuilder.GeneratedKt").getMethod("providers").invoke(null)
          as List<Any?>
      // Three weights for each of the two families, every one reading the one provider.
      assertEquals(6, providers.size)
      assertTrue(providers.all { it != null }, "a family read the provider before it was set")
      assertEquals(1, providers.toSet().size)
    }
  }
}
