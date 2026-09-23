package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * A capability catalog read from disk, used in place of the packaged one with the same system id.
 *
 * The offline hosts author against the three catalogs compiled into them. A catalog repository that
 * has just regenerated its `*-capabilities-v1.json` had no way to see it in the editor short of
 * rebuilding the editor with the file copied in. With an override the packaged catalog's seeds and
 * templates still apply — which is why the system id must be one this host packages — but the
 * palette, properties and validation come from the file.
 */
class CatalogOverride private constructor(val systemId: String, val text: String) {
  companion object {
    /**
     * Reads and checks [path]: it must decode as a released capability catalog, parse as the
     * editor's catalog, and name a system id this host packages.
     */
    fun read(path: Path): CatalogOverride {
      val text = Files.readString(path)
      val capability = runCatching {
        lenientJson.decodeFromString(CatalogCapabilityV1.serializer(), text)
      }
        .getOrElse {
          throw IllegalArgumentException(
            "${path.fileName} is not a capability catalog: ${it.message}",
            it,
          )
        }
      val systemId = capability.benchmark.catalogSystemId
      require(OfflineCatalog.entries.any { it.systemId == systemId }) {
        "${path.fileName} describes catalog '$systemId'; an override replaces one of " +
          OfflineCatalog.entries.joinToString { it.systemId }
      }
      CapabilityCatalogParser.parse(text)
      return CatalogOverride(systemId, text)
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }
  }
}
