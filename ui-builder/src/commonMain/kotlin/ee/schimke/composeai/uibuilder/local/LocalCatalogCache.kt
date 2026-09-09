package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The catalog, from the server when it answers and from this browser when it does not.
 *
 * This is the whole of what "mostly offline" rests on. A design is a list of component ids and
 * property values; what those *mean* — which components exist, what each one accepts, which of them
 * the Wasm canvas can draw — is the catalog, and the browser has no way to derive it. So the first
 * successful load of a catalog is remembered, and every later open prefers the network and falls
 * back to what was remembered. Online, that is a cache. Offline, it is the difference between an
 * editor and a blank page.
 *
 * Network-first rather than cache-first on purpose: a catalog is pinned by revision in the document
 * and a stale one would quietly disagree with the server the next time the design is opened live.
 * The stored copy is a fallback, never the preferred answer.
 *
 * A write that the browser refuses is not an error here. It means the next offline open will not
 * find a catalog, which is worth saying in the status line and not worth failing an online session
 * over.
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
 * One remembered text resource, for the small server-served files a local session also needs.
 *
 * The device presets the Screen inspector offers and the operations fixture every new design is
 * seeded from are both static files this page fetches once. Neither is worth a schema of its own,
 * and both are the difference between a complete offline editor and one with a menu missing, so
 * they go through the same network-first rule the catalog does.
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
