package ee.schimke.composeai.uibuilder.intellij

import java.io.InputStream

/**
 * How much of a `.json` file [sniffDesignSchema] is given. The writer's header — `schema` is the
 * first key it emits — fits in a few hundred bytes.
 */
internal const val DESIGN_HEADER_SNIFF_BYTES = 4096

private val schemaPattern = Regex(""""schema"\s*:\s*"([^"\\]{1,128})"""")

/**
 * The schema a file declares near its head, read without decoding the document.
 *
 * IntelliJ asks whether a file is a design from `FileEditorProvider.accept`, from the JSON schema
 * provider and from the structure view, for every JSON file a person opens and often on the EDT.
 * Decoding the whole `DesignDocumentV1` to answer that made every `package.json` and every large
 * fixture pay a full parse. Null for a window that declares no `schema`, which is the answer for
 * almost every JSON file in a project. A match is only a claim: the editor decodes the document
 * before it offers to open it.
 */
internal fun sniffDesignSchema(head: CharSequence): String? =
  schemaPattern.find(head)?.groupValues?.get(1)

/** How far [scanForDesignSchema] reads before giving up on a file. */
internal const val DESIGN_SCAN_LIMIT_BYTES = 8 * 1024 * 1024

private val designSchemaPattern =
  Regex(""""schema"\s*:\s*"(compose-ui-builder-document/[^"\\]{1,96})"""")

/**
 * The UI Builder schema a document declares anywhere in its first [limit] bytes.
 *
 * The head check above answers for every file this editor writes, which puts `schema` first. A
 * valid document may put it anywhere — key order means nothing in JSON, and an agent or a formatter
 * may reorder keys — so a file whose head is inconclusive is scanned the rest of the way for a UI
 * Builder schema value. It is a text scan in chunks, never a decode, and only a
 * `compose-ui-builder-document/...` value counts, so an unrelated `schema` key cannot claim a file.
 */
internal fun scanForDesignSchema(
  input: InputStream,
  limit: Int = DESIGN_SCAN_LIMIT_BYTES,
): String? {
  val buffer = ByteArray(64 * 1024)
  // Enough of the previous chunk to catch a declaration split across the boundary.
  var carry = ""
  var read = 0
  while (read < limit) {
    val count = input.read(buffer, 0, minOf(buffer.size, limit - read))
    if (count <= 0) return null
    read += count
    val window = carry + String(buffer, 0, count, Charsets.UTF_8)
    designSchemaPattern.find(window)?.let {
      return it.groupValues[1]
    }
    carry = window.takeLast(256)
  }
  return null
}
