package ee.schimke.composeai.uibuilder.intellij

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
