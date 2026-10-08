package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1

/**
 * The pins the Kotlin catalogs this build synthesises give the designs created against them,
 * frozen.
 *
 * A stored design names the exact catalog reference it was created against, and `resolve` accepts
 * only a reference it can name (#796). While a catalog is synthesised that reference is computed
 * from the generator; once the catalog is catalog-owned the generator no longer runs, and once the
 * cutover deletes it there is nothing left to compute it from. So it is written down here, and
 * `CatalogCutoverReadinessTest` holds every row equal to what the generator produces for as long as
 * both exist — the same freeze-before-delete order `UI_BUILDER_CATALOG_CUTOVER.md` uses for every
 * other fact that moves.
 *
 * Only catalog ids this build has ever synthesised are here. A catalog that was only ever published
 * has no legacy pin to honour.
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
