package ee.schimke.composeai.uibuilder.export

import ee.schimke.flexpress.VariableFont
import ee.schimke.flexpress.codegen.CodegenTarget
import ee.schimke.flexpress.codegen.VariableFontCodegen
import java.util.concurrent.ConcurrentHashMap

/**
 * [VariableFontSourceGenerator] on flexpress's own code generator: each request becomes the Kotlin
 * file `VariableFontCodegen` writes for it, its outline worked out now from the font file.
 *
 * The fonts are read through [readFont], by [VariableFontText.Font.file]. [fromClasspath] reads the
 * copies this module's JVM jar carries (the vendored `assets/rc-fonts` files, staged by the build),
 * which is what an export host such as compose-preview-server has: it has this jar, not the UI
 * builder's. A font that cannot be read refuses its requests rather than throwing.
 */
class FlexpressVariableFontSources(private val readFont: (file: String) -> ByteArray?) :
  VariableFontSourceGenerator {

  private val fonts = ConcurrentHashMap<VariableFontText.Font, Result<VariableFont>>()

  override fun generate(
    request: VariableFontText.Request,
    packageName: String,
    mode: VariableFontExportMode,
  ): VariableFontSource {
    val spec = request.spec
    VariableFontText.problems(spec).firstOrNull()?.let {
      return VariableFontSource.Refused(it)
    }
    val font =
      font(spec.font).getOrElse {
        return VariableFontSource.Refused(
          "${spec.font.family} (${spec.font.file}) could not be read: ${it.message}"
        )
      }
    val missing =
      spec.text.codePoints().toArray().distinct().filter {
        font.glyphId(it) == 0 && !Character.isWhitespace(it)
      }
    if (missing.isNotEmpty()) {
      val characters = missing.joinToString(" ") { String(Character.toChars(it)) }
      return VariableFontSource.Refused(
        "${spec.font.family} has no glyph for $characters in \"${spec.text}\""
      )
    }
    val source =
      VariableFontCodegen.generate(
        packageName = packageName,
        functionName = request.functionName,
        font = font,
        fontName = spec.font.family,
        text = spec.text,
        axes = spec.animated,
        location = spec.location,
        fileHeader = FILE_HEADER,
        target =
          when (spec.target) {
            VariableFontText.Target.COMPOSE_UI -> CodegenTarget.ComposeUi
            VariableFontText.Target.REMOTE -> CodegenTarget.RemoteCompose
          },
        standalone = mode == VariableFontExportMode.STANDALONE,
      )
    return VariableFontSource.Generated("${request.functionName}.kt", source)
  }

  private fun font(font: VariableFontText.Font): Result<VariableFont> =
    fonts.getOrPut(font) {
      runCatching {
        val bytes = checkNotNull(readFont(font.file)) { "no such file" }
        VariableFont.parse(bytes)
      }
    }

  companion object {
    private const val FILE_HEADER = "// Written by the Compose UI builder's export."

    /** Where the build stages the fonts in this module's JVM resources. */
    const val CLASSPATH_FONTS: String = "/ee/schimke/composeai/uibuilder/export/fonts/"

    /** The fonts this module carries, from [CLASSPATH_FONTS] on its own class loader. */
    fun fromClasspath(): FlexpressVariableFontSources = FlexpressVariableFontSources { file ->
      FlexpressVariableFontSources::class.java.getResourceAsStream(CLASSPATH_FONTS + file)?.use {
        it.readBytes()
      }
    }
  }
}
