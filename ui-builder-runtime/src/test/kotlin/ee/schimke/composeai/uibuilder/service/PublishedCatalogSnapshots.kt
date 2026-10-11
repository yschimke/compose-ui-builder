package ee.schimke.composeai.uibuilder.service

/**
 * The published files [PublishedUiBuilderCatalog]'s equivalence tests compose, as one captured set
 * under test resources `published-catalog/`, not `docs/design/fixtures/ui-builder/`: policy, record
 * and frozen catalog only agree when captured together, while the docs fixtures move on separate
 * schedules. Re-capture as a reviewed change: copy the current files and read the diff.
 */
internal const val PUBLISHED_CATALOG_FIXTURES: String = "/published-catalog/"

internal fun publishedCatalogFixture(name: String): String =
  checkNotNull(
      PublishedUiBuilderCatalog::class.java.getResource(PUBLISHED_CATALOG_FIXTURES + name)
    ) {
      "missing test resource $PUBLISHED_CATALOG_FIXTURES$name"
    }
    .readText()
