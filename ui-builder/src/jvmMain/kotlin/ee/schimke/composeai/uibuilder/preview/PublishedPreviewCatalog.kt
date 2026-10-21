package ee.schimke.composeai.uibuilder.preview

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.host.PublishedCatalogStore

/**
 * The catalog a JVM preview draws, composed from its delivery branch and cached for offline use.
 */
internal fun publishedPreviewCatalog(systemId: String): CapabilityCatalog =
  when (val load = PublishedCatalogStore.shared.load(systemId)) {
    is PublishedCatalogStore.Load.Loaded -> CapabilityCatalogParser.parse(load.capabilityJson)
    is PublishedCatalogStore.Load.Unavailable -> error(load.reason)
  }

/** The seed templates [systemId] publishes beside the catalog [publishedPreviewCatalog] draws. */
internal fun publishedPreviewTemplates(systemId: String): CatalogSeedTemplates =
  when (val load = PublishedCatalogStore.shared.load(systemId)) {
    is PublishedCatalogStore.Load.Loaded -> load.templates
    is PublishedCatalogStore.Load.Unavailable -> error(load.reason)
  }
