package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.CompositionLocalProvider
import ee.schimke.composeai.discovery.ComponentRecordFile
import java.util.ServiceLoader
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.JvmResourceReader
import org.jetbrains.compose.resources.LocalResourceReader

/** Opt-in JVM entry point shipped by a trusted catalog's executable adapter bundle. */
interface JvmCanvasAdapterProvider {
  val catalogId: String

  /** The host passes the inventory from the same bundle; discovery is not run in the host. */
  fun registry(records: ComponentRecordFile): CanvasAdapterRegistry
}

/** Load exactly one provider for this catalog from a host-supplied bundle class loader. */
@OptIn(ExperimentalResourceApi::class)
fun loadJvmCanvasAdapterRegistry(
  catalogId: String,
  records: ComponentRecordFile,
  classLoader: ClassLoader,
  requiredAdapterIds: Set<String>,
): CanvasAdapterRegistry {
  require(catalogId.isNotBlank()) { "catalog id must not be blank" }
  val providers =
    ServiceLoader.load(JvmCanvasAdapterProvider::class.java, classLoader).filter {
      it.catalogId == catalogId
    }
  require(providers.size == 1) {
    "expected one JVM adapter provider for $catalogId, found ${providers.size}"
  }
  val supplied = providers.single().registry(records)
  val resourceReader = JvmResourceReader(classLoader)
  return canvasAdapterRegistry {
    for (id in requiredAdapterIds) {
      val adapter = requireNotNull(supplied[id]) { "$catalogId has no executable adapter for $id" }
      register(id) {
        val scope = this
        CompositionLocalProvider(LocalResourceReader provides resourceReader) { adapter(scope) }
      }
    }
  }
}
