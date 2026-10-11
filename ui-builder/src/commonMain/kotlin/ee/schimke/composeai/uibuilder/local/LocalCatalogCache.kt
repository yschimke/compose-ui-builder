package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The catalog, from the server when it answers and from this browser when it doesn't: the first
 * successful load is remembered, and later opens try the network first and fall back to it. This is
 * what makes the editor work offline.
 *
 * Network-first because a catalog is pinned by revision and a stale one could disagree with the
 * server. A refused write isn't an error: the next offline open just won't find a catalog, which is
 * reported in the status line.
 */
class CachingLocalCatalogSource(
  private val storage: LocalDesignStorage,
  private val remote: LocalCatalogSource,
  private val json: Json = localDesignJson,
) : LocalCatalogSource {
  /** True once a load has fallen back to the stored copy, which is what makes a session offline. */
  var servedFromStorage: Boolean = false
    private set

  override suspend fun catalogs(): List<CatalogCapabilityV1> =
    try {
      val fetched = remote.catalogs()
      servedFromStorage = false
      remember(fetched)
      fetched
    } catch (failure: Exception) {
      val stored =
        stored()
          ?: throw LocalDesignStorageException(
            "this browser has no stored catalog to open a design with",
            failure,
          )
      servedFromStorage = true
      stored
    }

  private fun remember(catalogs: List<CatalogCapabilityV1>) {
    try {
      storage.write(
        CATALOG_KEY,
        json.encodeToString(ListSerializer(CatalogCapabilityV1.serializer()), catalogs),
      )
    } catch (_: LocalDesignStorageException) {
      // Kept out of the way of an online session: see the class comment.
    }
  }

  private fun stored(): List<CatalogCapabilityV1>? =
    storage.read(CATALOG_KEY)?.let {
      try {
        json.decodeFromString(ListSerializer(CatalogCapabilityV1.serializer()), it)
      } catch (_: Exception) {
        null
      }
    }

  companion object {
    const val CATALOG_KEY: String = "ui-builder.local.catalogs"
  }
}

/**
 * One remembered text resource, for the small static files a local session also needs (the device
 * presets and the seed operations fixture), with the same network-first rule.
 */
class CachedLocalText(private val storage: LocalDesignStorage) {
  suspend fun text(url: String, fetch: suspend (String) -> String): String {
    val key = "$TEXT_KEY_PREFIX$url"
    return try {
      fetch(url).also {
        try {
          storage.write(key, it)
        } catch (_: LocalDesignStorageException) {}
      }
    } catch (failure: Exception) {
      storage.read(key)
        ?: throw LocalDesignStorageException("this browser has not stored $url", failure)
    }
  }

  companion object {
    const val TEXT_KEY_PREFIX: String = "ui-builder.local.resource."
  }
}
