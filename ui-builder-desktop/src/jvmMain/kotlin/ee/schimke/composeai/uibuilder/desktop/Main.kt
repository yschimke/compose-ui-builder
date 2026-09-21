package ee.schimke.composeai.uibuilder.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.UiBuilderEditor
import ee.schimke.composeai.uibuilder.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.UiBuilderReducer
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.local.FileLocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderService
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val DESKTOP_DESIGN_ID = "desktop-workspace"
private const val ACTOR_ID = "desktop-user"
private const val CLIENT_ID = "desktop-client"

/** Launches the native, offline UI Builder desktop host. */
fun main(args: Array<String>) = application {
  val options = DesktopLaunchOptions.parse(args)
  Window(onCloseRequest = ::exitApplication, title = "Compose UI Builder") {
    MaterialTheme {
      Surface(Modifier.fillMaxSize()) {
        OfflineUiBuilderApp(
          storagePath = designStorePath(),
          sessionLabel = "Desktop offline · saved locally",
          remoteServer = options.remoteServer,
        )
      }
    }
  }
}

/**
 * Hosts the offline editor without tying it to a windowing toolkit.
 *
 * Desktop and IntelliJ hosts decide where a workspace belongs, then pass that path across this
 * boundary. In particular, this composable deliberately has no IntelliJ Platform types in its API.
 */
@Composable
fun OfflineUiBuilderApp(
  storagePath: Path,
  sessionLabel: String,
  catalogSystemId: String = OfflineCatalog.M3.systemId,
  remoteServer: String? = null,
) {
  val offlineCatalog = remember(catalogSystemId) { OfflineCatalog.forSystem(catalogSystemId) }
  val catalogText = remember(offlineCatalog) { resourceText(offlineCatalog.capabilitiesResource) }
  val catalog = remember(catalogText) { CapabilityCatalogParser.parse(catalogText) }
  val catalogCapability =
    remember(catalogText) { Json.decodeFromString(CatalogCapabilityV1.serializer(), catalogText) }
  val service =
    remember(catalogCapability) {
      LocalUiBuilderService(
        store = LocalDesignStore(FileLocalDesignStorage(storagePath)),
        catalogs = { listOf(catalogCapability) },
        clock = System::currentTimeMillis,
      )
    }
  val remotePreview = remember(remoteServer) { remoteServer?.let(::RemotePreviewClient) }
  var snapshot by remember { mutableStateOf<SnapshotResponseV1?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  val submissions = remember { Channel<EditorSubmission>(Channel.UNLIMITED) }
  DisposableEffect(submissions) { onDispose { submissions.close() } }

  suspend fun refresh() {
    when (val result = service.execute(OpenDesignRequestV1(DESKTOP_DESIGN_ID))) {
      is SnapshotResponseV1 -> snapshot = result
      is ErrorResponseV1 -> failure = result.error.message
      else -> failure = "unexpected response while opening the desktop design"
    }
  }

  LaunchedEffect(service) {
    when (val open = service.execute(OpenDesignRequestV1(DESKTOP_DESIGN_ID))) {
      is SnapshotResponseV1 -> snapshot = open
      is ErrorResponseV1 -> {
        val seed =
          offlineCatalog.seed(
            designId = DESKTOP_DESIGN_ID,
            catalogRevision = catalog.benchmark.catalogRevision,
            nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
          )
        when (val created = service.create(seed)) {
          is SnapshotResponseV1 -> snapshot = created
          is ErrorResponseV1 -> failure = created.error.message
          else -> failure = "unexpected response while creating the desktop design"
        }
      }
      else -> failure = "unexpected response while opening the desktop design"
    }
  }

  // One protocol command at a time, just like the browser session. The next submission must use
  // the revision that the previous one produced, otherwise a quick sequence of edits conflicts
  // with its own locally persisted history.
  LaunchedEffect(service, submissions) {
    for (submission in submissions) {
      val baseRevision = snapshot?.snapshot?.state?.document?.revision?.toInt() ?: continue
      when (
        val result =
          service.execute(
            ApplyOperationRequestV1(
              submission.toProtocolSubmission(ACTOR_ID, CLIENT_ID, baseRevision)
            )
          )
      ) {
        is OperationOutcomeResponseV1 -> refresh()
        is ErrorResponseV1 -> failure = result.error.message
        else -> failure = "unexpected response while saving the desktop design"
      }
    }
  }

  snapshot?.let { current ->
    var previewDocument by
      remember(current.snapshot.state.document.revision) {
        mutableStateOf(current.snapshot.state.document.toUiBuilderDocument())
      }
    UiBuilderEditor(
      document = current.snapshot.state.document.toUiBuilderDocument(),
      catalog = catalog,
      actorId = ACTOR_ID,
      clientId = CLIENT_ID,
      operationIdPrefix = CLIENT_ID,
      sessionLabel = sessionLabel,
      onRequestNativeRender =
        remotePreview?.let { client -> { shape -> client.render(previewDocument, shape) } },
      onStateChanged = { state -> previewDocument = state.collaboration.document },
      onSubmission = { submissions.trySend(it) },
    )
  }
  failure?.let { Text(it) }
}

private fun fixtureDocument(): UiBuilderDocument =
  UiBuilderReducer.replay(
      Json.parseToJsonElement(resourceText("jetcaster-discover-operations-v1.json")).jsonObject
    )
    .document

/** A packaged catalog that the offline hosts can author against without a server. */
public enum class OfflineCatalog(
  val systemId: String,
  val capabilitiesResource: String,
  private val templateId: String,
) {
  M3("m3-catalog", "m3-catalog-capabilities-v1.json", UiBuilderNewDesignSeed.DEFAULT_TEMPLATE),
  WEAR_M3("wear-m3", "wear-m3-capabilities-v1.json", UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE);

  fun seed(
    designId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
  ): UiBuilderDocument =
    if (this == M3) {
      fixtureDocument().copy(id = designId, title = "Desktop workspace")
    } else {
      UiBuilderNewDesignSeed.document(
        designId = designId,
        catalogSystemId = systemId,
        templateId = templateId,
        catalogRevision = catalogRevision,
        nativeRuntimeId = nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(resourceText("jetcaster-discover-operations-v1.json")).jsonObject,
      )
    }

  companion object {
    fun forSystem(systemId: String): OfflineCatalog =
      entries.firstOrNull { it.systemId == systemId }
        ?: error("offline UI Builder has no packaged catalog '$systemId'")
  }
}

private fun resourceText(name: String): String =
  checkNotNull(object {}.javaClass.getResource("/$name")) { "missing desktop resource $name" }
    .readText()

private fun designStorePath(): Path =
  Path.of(System.getProperty("user.home"), ".compose-preview", "ui-builder-desktop")

private data class DesktopLaunchOptions(val remoteServer: String?) {
  companion object {
    fun parse(args: Array<String>): DesktopLaunchOptions {
      if (args.isEmpty()) return DesktopLaunchOptions(null)
      require(args.size == 2 && args[0] == "--server") {
        "usage: Compose UI Builder [--server https://preview.coo.ee]"
      }
      return DesktopLaunchOptions(validatedServerOrigin(args[1]).toString())
    }
  }
}
