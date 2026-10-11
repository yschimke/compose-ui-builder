package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1

/**
 * The pins the synthesised Kotlin catalogs gave designs created against them, frozen so `resolve`
 * can still accept them once the generators stop running. `CatalogCutoverReadinessTest` holds every
 * row equal to the generator while both exist. Only ids this build ever synthesised are listed.
 */
internal object LegacySynthesisedReferences {

  private const val CANDIDATE = "candidate"

  private val references: Map<String, CatalogReferenceV1> =
    listOf(
        reference(CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID, CANDIDATE),
        reference(
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
          "wear-widget-scaffolds-v1",
        ),
        reference(
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
          "wear-screen-scaffold-v1",
        ),
        reference(
          CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
          "a2ui-basic-catalog-v0.9.1",
        ),
      )
      .associateBy { it.systemId }

  /** Every frozen pin, by catalog id. */
  val all: Map<String, CatalogReferenceV1>
    get() = references

  /**
   * The pin a design created against [systemId]'s synthesised catalog carries, if it ever had one.
   */
  fun forCatalog(systemId: String): CatalogReferenceV1? = references[systemId]

  private fun reference(systemId: String, revision: String) =
    CatalogReferenceV1(
      systemId = systemId,
      catalogRevision = revision,
      capabilityDigest = CurrentM3UiBuilderCatalogExecutor.CURRENT_CAPABILITY_DIGEST,
      nativeRuntimeId = CANDIDATE,
    )
}
