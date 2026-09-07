package ee.schimke.composeai.uibuilder

/**
 * The top-level `val`s an inlined picture becomes: its bytes, and the decode that turns them into
 * one.
 *
 * Shared by the two widget emitters rather than owned by either, because the reason to inline is
 * the same shape twice. [WearWidgetCodeExporter] inlines a **background**: a widget is drawn by the
 * system host, out of the app's process and without its resources, so a name resolved at draw time
 * is not there to resolve and the pixels have to travel inside the document
 * (yschimke/compose-preview-server#523). [WearWidgetNativePreviewExporter] inlines the content
 * pictures too, because nothing downstream of the native lane can pass an argument.
 *
 * Chunked into a list of literals joined at runtime rather than one long `const val`, because a JVM
 * string constant is capped at 65535 **bytes** of modified UTF-8 and a photograph passes that
 * easily. Concatenating literals with `+` would not help — the compiler folds those into the single
 * constant the cap applies to — so the chunks are joined by code instead, and a picture of any size
 * compiles.
 *
 * One decoder per file, not per picture, and `NO_WRAP` because the encoder above emits no line
 * breaks.
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
