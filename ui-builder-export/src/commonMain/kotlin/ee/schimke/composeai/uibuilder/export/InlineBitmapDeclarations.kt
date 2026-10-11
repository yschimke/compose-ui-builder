package ee.schimke.composeai.uibuilder.export

/**
 * The top-level `val`s an inlined picture becomes: its bytes, and the decode into a bitmap. Shared
 * by [WearWidgetCodeExporter] (backgrounds, drawn by a host without the app's resources) and
 * [WearWidgetNativePreviewExporter] (content pictures, since nothing can pass an argument).
 *
 * Chunked literals joined at runtime, because a JVM string constant is capped at 65535 bytes of
 * modified UTF-8 (and `+` of literals is folded into one constant). One decoder per file; `NO_WRAP`
 * because the encoder emits no line breaks.
 */
internal fun inlineBitmapDeclarations(
  assets: List<RemoteContentEmitter.InlineAsset>
): List<String> {
  if (assets.isEmpty()) return emptyList()
  val blocks = mutableListOf<String>()
  assets.forEach { asset ->
    // The bytes first: a top-level `val` is initialised in declaration order, so a decode that read
    // its constant from above it would compile to "must be initialized".
    blocks += buildString {
      appendLine("/** The design's `${asset.assetKey.escapeComment()}` asset, inlined. */")
      appendLine("private val ${asset.identifier.uppercaseBitmapConstant()}: String =")
      appendLine("${INLINE_BITMAP_INDENT}listOf(")
      asset.base64.chunked(INLINE_BITMAP_BASE64_CHUNK).forEach {
        appendLine("$INLINE_BITMAP_INDENT$INLINE_BITMAP_INDENT\"$it\",")
      }
      appendLine("$INLINE_BITMAP_INDENT)")
      append("$INLINE_BITMAP_INDENT${INLINE_BITMAP_INDENT}.joinToString(\"\")")
    }
    blocks +=
      "private val ${asset.identifier}: RemoteImageBitmap =\n" +
        "${INLINE_BITMAP_INDENT}decodeInlineBitmap(${asset.identifier.uppercaseBitmapConstant()})"
  }
  blocks += buildString {
    appendLine("private fun decodeInlineBitmap(encoded: String): RemoteImageBitmap {")
    appendLine("${INLINE_BITMAP_INDENT}val bytes = Base64.decode(encoded, Base64.NO_WRAP)")
    appendLine("${INLINE_BITMAP_INDENT}return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)")
    appendLine("$INLINE_BITMAP_INDENT$INLINE_BITMAP_INDENT.asImageBitmap()")
    appendLine("$INLINE_BITMAP_INDENT$INLINE_BITMAP_INDENT.rb")
    append("}")
  }
  return blocks
}

/** `coverWide` becomes `COVER_WIDE_PNG`, the constant beside it. */
private fun String.uppercaseBitmapConstant(): String = buildString {
  this@uppercaseBitmapConstant.forEach {
    if (it.isUpperCase() && isNotEmpty()) append('_')
    append(it.uppercaseChar())
  }
  append("_PNG")
}

private const val INLINE_BITMAP_INDENT = "    "

/** ktfmt's own default, as [RemoteContentEmitter] keeps for the body. */
private const val INLINE_BITMAP_BASE64_CHUNK = 96
