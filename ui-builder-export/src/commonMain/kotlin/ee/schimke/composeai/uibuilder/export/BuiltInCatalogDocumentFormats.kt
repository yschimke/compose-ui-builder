package ee.schimke.composeai.uibuilder.export

/**
 * The document formats this build ships: the one place a format is listed. Everything else reads
 * them through [CatalogDocumentFormats], so adding a format is a new [CatalogDocumentFormat] and a
 * line here, and nothing in the editor, the runtime or the export routing.
 */
object BuiltInCatalogDocumentFormats {
  val ALL: CatalogDocumentFormats = CatalogDocumentFormats(listOf(KobentoDocumentFormat))
}
