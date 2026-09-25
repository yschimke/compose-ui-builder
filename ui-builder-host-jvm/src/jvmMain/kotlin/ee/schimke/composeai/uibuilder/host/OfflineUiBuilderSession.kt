package ee.schimke.composeai.uibuilder.host

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.DesignCommentBoard
import ee.schimke.composeai.uibuilder.editor.DesignCommentDraft
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.MaterialUiBuilderChrome
import ee.schimke.composeai.uibuilder.editor.UiBuilderChrome
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderNativeRender
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.local.FileLocalDesignStorage
import ee.schimke.composeai.uibuilder.local.InMemoryLocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.LocalUiBuilderService
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DESKTOP_DESIGN_ID = "desktop-workspace"
private const val ACTOR_ID = "desktop-user"
private const val CLIENT_ID = "desktop-client"

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
  /** What a workspace that does not exist yet starts as; null for the catalog's own starter. */
  templateId: String? = null,
  chrome: UiBuilderChrome = MaterialUiBuilderChrome,
  initialPanes: Set<EditorPane> = setOf(EditorPane.Editor),
  availablePanes: Set<EditorPane> = EditorPane.entries.toSet(),
  openDefaultPreview: Boolean = true,
) {
  val session =
    remember(storagePath, catalogSystemId, remoteServer, templateId) {
      OfflineUiBuilderSession(storagePath, catalogSystemId, remoteServer, templateId)
    }
  DisposableEffect(session) { onDispose { session.close() } }
  OfflineUiBuilderSessionView(
    session = session,
    sessionLabel = sessionLabel,
    chrome = chrome,
    initialPanes = initialPanes,
    availablePanes = availablePanes,
    openDefaultPreview = openDefaultPreview,
  )
}

/** One authoritative design session shared by every IDE view that displays it. */
interface UiBuilderSession : AutoCloseable {
  val catalog: CapabilityCatalog
  val snapshot: StateFlow<SnapshotResponseV1?>
  val failure: StateFlow<String?>
  val actorId: String
  val clientId: String
  val operationIdPrefix: String
  val nativeRenderAvailable: Boolean
  val commentsAvailable: Boolean
  val comments: StateFlow<DesignCommentBoard>
  val commentStatus: StateFlow<String?>

  fun submit(submission: EditorSubmission)

  fun postComment(draft: DesignCommentDraft)

  fun resolveCommentThread(threadId: String, resolved: Boolean)

  suspend fun renderNative(
    document: UiBuilderDocument,
    hostShape: WearWidgetHostShape,
  ): UiBuilderNativeRender?
}

/** One persisted offline design shared by every IDE view that displays it. */
class OfflineUiBuilderSession
private constructor(
  storage: LocalDesignStorage,
  catalogSystemId: String,
  remoteServer: String?,
  private val designId: String,
  private val initialDocument: UiBuilderDocument?,
  private val templateId: String?,
  catalogOverride: CatalogOverride?,
  private val onDocumentCommitted:
    suspend (ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1) -> Unit,
) : UiBuilderSession {

  constructor(
    storagePath: Path,
    catalogSystemId: String = OfflineCatalog.M3.systemId,
    remoteServer: String? = null,
    templateId: String? = null,
    /** Capabilities read from disk; used when its system id is [catalogSystemId]. */
    catalogOverride: CatalogOverride? = null,
  ) : this(
    storage = FileLocalDesignStorage(storagePath),
    catalogSystemId = catalogSystemId,
    remoteServer = remoteServer,
    designId = DESKTOP_DESIGN_ID,
    initialDocument = null,
    templateId = templateId,
    catalogOverride = catalogOverride,
    onDocumentCommitted = {},
  )

  companion object {
    /** Opens a published project document as the primary persisted artifact. */
    fun projectDocument(
      document: UiBuilderDocument,
      remoteServer: String? = null,
      catalogOverride: CatalogOverride? = null,
      onDocumentCommitted:
        suspend (ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1) -> Unit,
    ): OfflineUiBuilderSession =
      OfflineUiBuilderSession(
        storage = InMemoryLocalDesignStorage(),
        catalogSystemId =
          requireNotNull(document.catalogPin["systemId"]?.jsonPrimitive?.contentOrNull) {
            "design ${document.id} names no catalog systemId"
          },
        remoteServer = remoteServer,
        designId = document.id,
        initialDocument = document,
        templateId = null,
        catalogOverride = catalogOverride,
        onDocumentCommitted = onDocumentCommitted,
      )
  }

  internal val offlineCatalog = OfflineCatalog.forSystem(catalogSystemId)
  private val appliedOverride = catalogOverride?.takeIf { it.systemId == catalogSystemId }
  private val catalogText =
    appliedOverride?.text ?: resourceText(offlineCatalog.capabilitiesResource)
  override val catalog = CapabilityCatalogParser.parse(catalogText)
  private val store = LocalDesignStore(storage)
  private val catalogCapability = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(CatalogCapabilityV1.serializer(), catalogText)
  private val service =
    LocalUiBuilderService(
      store = store,
      catalogs = { listOf(catalogCapability) },
      clock = System::currentTimeMillis,
    )
  internal val remotePreview = remoteServer?.let(::RemotePreviewClient)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val submissions = Channel<EditorSubmission>(Channel.UNLIMITED)
  private val mutableSnapshot = MutableStateFlow<SnapshotResponseV1?>(null)
  override val snapshot = mutableSnapshot.asStateFlow()
  private val mutableFailure = MutableStateFlow<String?>(null)
  override val failure = mutableFailure.asStateFlow()
  override val actorId: String = ACTOR_ID
  override val clientId: String = CLIENT_ID
  override val operationIdPrefix: String = CLIENT_ID
  override val nativeRenderAvailable: Boolean = remotePreview != null
  override val commentsAvailable: Boolean = false
  private val mutableComments = MutableStateFlow(DesignCommentBoard())
  override val comments = mutableComments.asStateFlow()
  private val mutableCommentStatus =
    MutableStateFlow<String?>(
      "Comments are available when this design is opened from a preview server."
    )
  override val commentStatus = mutableCommentStatus.asStateFlow()

  private val worker: Job

  init {
    scope.launch { openOrCreate() }
    // One protocol command at a time, just like the browser session. The next submission must use
    // the revision the previous one produced, otherwise quick edits conflict with their own store.
    worker = scope.launch {
      for (submission in submissions) {
        // An edit made while the design is still opening waits for it rather than being dropped:
        // the channel is the queue, and the first snapshot is the revision it applies against.
        val baseRevision =
          mutableSnapshot.filterNotNull().first().snapshot.state.document.revision.toInt()
        when (
          val result =
            service.execute(
              ApplyOperationRequestV1(
                submission.toProtocolSubmission(ACTOR_ID, CLIENT_ID, baseRevision)
              )
            )
        ) {
          is OperationOutcomeResponseV1 -> refresh(persist = true)
          is ErrorResponseV1 -> mutableFailure.value = result.error.message
          else -> mutableFailure.value = "unexpected response while saving the desktop design"
        }
      }
    }
  }

  override fun submit(submission: EditorSubmission) {
    submissions.trySend(submission)
  }

  override fun postComment(draft: DesignCommentDraft) = Unit

  override fun resolveCommentThread(threadId: String, resolved: Boolean) = Unit

  override suspend fun renderNative(
    document: UiBuilderDocument,
    hostShape: WearWidgetHostShape,
  ): UiBuilderNativeRender? = remotePreview?.render(document, hostShape)

  private suspend fun openOrCreate() {
    // A catalog read from disk is usually a regeneration of the packaged one, with a new revision.
    // A design pinned to the old revision would then fail every export on CATALOG_PIN_MISMATCH
    // while being edited against the new capabilities — so the design follows the catalog it is
    // being authored against, as a stored record and as a project document alike.
    if (appliedOverride != null) {
      // A store that cannot be rewritten (read-only, full) still opens the design, at its old pin,
      // and says why exports may refuse it, rather than leaving the editor blank.
      runCatching {
        store.read(designId)?.let { record ->
          val repinned = record.seed.pinnedTo(catalog)
          if (repinned != record.seed) store.write(record.copy(seed = repinned))
        }
      }
        .onFailure {
          mutableFailure.value =
            "could not re-pin the design to ${catalog.benchmark.catalogRevision}: " +
              (it.message ?: it::class.simpleName)
        }
    }
    when (val open = service.execute(OpenDesignRequestV1(designId))) {
      is SnapshotResponseV1 -> mutableSnapshot.value = open
      is ErrorResponseV1 -> {
        val seed =
          initialDocument?.let { if (appliedOverride != null) it.pinnedTo(catalog) else it }
            ?: offlineCatalog.seed(
              designId = designId,
              catalogRevision = catalog.benchmark.catalogRevision,
              nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
              templateId = templateId,
            )
        when (val created = service.create(seed)) {
          is SnapshotResponseV1 -> mutableSnapshot.value = created
          is ErrorResponseV1 -> mutableFailure.value = created.error.message
          else -> mutableFailure.value = "unexpected response while creating the desktop design"
        }
      }
      else -> mutableFailure.value = "unexpected response while opening the desktop design"
    }
  }

  private suspend fun refresh(persist: Boolean = false) {
    when (val result = service.execute(OpenDesignRequestV1(designId))) {
      is SnapshotResponseV1 -> {
        mutableSnapshot.value = result
        if (persist) {
          try {
            onDocumentCommitted(result.snapshot.state.document)
            mutableFailure.value = null
          } catch (failure: Exception) {
            mutableFailure.value =
              "the design changed in memory but could not be written: " +
                (failure.message ?: failure::class.simpleName)
          }
        }
      }
      is ErrorResponseV1 -> mutableFailure.value = result.error.message
      else -> mutableFailure.value = "unexpected response while opening the desktop design"
    }
  }

  /**
   * Stops taking edits, lets the ones already queued land, then stops.
   *
   * An edit is queued the moment it is made and persisted when the worker reaches it, so closing
   * straight after an edit — New, Open, closing the last IDE editor — used to cancel the worker
   * with the edit still in the queue. Draining is bounded: a design that never opened has nothing
   * to apply its queue to, and must not hold the caller forever.
   */
  override fun close() {
    submissions.close()
    runBlocking { withTimeoutOrNull(CLOSE_DRAIN_TIMEOUT) { worker.join() } }
    scope.cancel()
  }
}

/** How long [OfflineUiBuilderSession.close] waits for queued edits to be persisted. */
private val CLOSE_DRAIN_TIMEOUT = 2.seconds

/**
 * This document pinned to [catalog]'s revision and runtime. A capability digest written as the old
 * revision follows it; the `candidate` placeholder, which every revision accepts, is left alone.
 */
internal fun UiBuilderDocument.pinnedTo(catalog: CapabilityCatalog): UiBuilderDocument {
  val benchmark = catalog.benchmark
  val previousRevision = catalogPin["catalogRevision"]?.jsonPrimitive?.contentOrNull
  val pin =
    catalogPin.toMutableMap().apply {
      put("systemId", JsonPrimitive(benchmark.catalogSystemId))
      put("catalogRevision", JsonPrimitive(benchmark.catalogRevision))
      put("nativeRuntimeId", JsonPrimitive(benchmark.nativeRuntimeId))
      val digest = get("capabilityDigest")?.jsonPrimitive?.contentOrNull
      if (digest != null && digest == previousRevision) {
        put("capabilityDigest", JsonPrimitive(benchmark.catalogRevision))
      }
    }
  return if (pin == catalogPin) this else copy(catalogPin = JsonObject(pin))
}

/** Displays one view of a shared [UiBuilderSession]. */
@Composable
fun OfflineUiBuilderSessionView(
  session: UiBuilderSession,
  sessionLabel: String,
  chrome: UiBuilderChrome = MaterialUiBuilderChrome,
  initialPanes: Set<EditorPane> = setOf(EditorPane.Editor),
  availablePanes: Set<EditorPane> = EditorPane.entries.toSet(),
  openDefaultPreview: Boolean = true,
  initialComponentsOpen: Boolean = false,
  initialLayersOpen: Boolean = false,
  initialInspectorOpen: Boolean = false,
  /**
   * How this host gets a design out. Defaults to rendering in-process and saving through a native
   * dialog ([DesktopExportHost]); null hides the Export menu.
   */
  exportHost: ((document: () -> UiBuilderDocument?) -> UiBuilderExportHost)? = { document ->
    DesktopExportHost(session.catalog, document)
  },
) {
  val snapshot by session.snapshot.collectAsState()
  val failure by session.failure.collectAsState()
  val comments by session.comments.collectAsState()
  val commentStatus by session.commentStatus.collectAsState()
  snapshot?.let { current ->
    var previewDocument by
      remember(current.snapshot.state.document.revision) {
        mutableStateOf(current.snapshot.state.document.toUiBuilderDocument())
      }
    val latestDocument by rememberUpdatedState(previewDocument)
    val export = remember(session, exportHost) { exportHost?.invoke { latestDocument } }
    UiBuilderEditor(
      document = current.snapshot.state.document.toUiBuilderDocument(),
      exportHost = export,
      catalog = session.catalog,
      chrome = chrome,
      actorId = session.actorId,
      clientId = session.clientId,
      operationIdPrefix = session.operationIdPrefix,
      sessionLabel = sessionLabel,
      initialPanes = initialPanes,
      availablePanes = availablePanes,
      openDefaultPreview = openDefaultPreview,
      initialComponentsOpen = initialComponentsOpen,
      initialLayersOpen = initialLayersOpen,
      initialInspectorOpen = initialInspectorOpen,
      onRequestNativeRender =
        if (session.nativeRenderAvailable) {
          { shape -> session.renderNative(previewDocument, shape) ?: UiBuilderNativeRender() }
        } else null,
      onStateChanged = { state -> previewDocument = state.collaboration.document },
      onSubmission = session::submit,
      comments = comments,
      onPostComment = if (session.commentsAvailable) session::postComment else null,
      onResolveCommentThread =
        if (session.commentsAvailable) session::resolveCommentThread else null,
      commentStatus = commentStatus,
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
  /** What a new workspace in this catalog starts as when no template is asked for. */
  val defaultTemplateId: String,
) {
  M3("m3-catalog", "m3-catalog-capabilities-v1.json", UiBuilderNewDesignSeed.DEFAULT_TEMPLATE),
  WEAR_M3("wear-m3", "wear-m3-capabilities-v1.json", UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE),
  REMOTE_M3("remote-m3", "remote-m3-capabilities-v1.json", "wear-widget-small");

  /**
   * Every template [seed] accepts, from the same seed the web host's New design form offers — in
   * the packaged vocabulary, because this host validates against [capabilitiesResource] and draws
   * with the in-process canvas rather than a catalog's published runtime.
   */
  val templateIds: Set<String>
    get() = UiBuilderNewDesignSeed.templateIds(systemId, UiBuilderNewDesignSeed.Vocabulary.PACKAGED)

  fun seed(
    designId: String,
    catalogRevision: String,
    nativeRuntimeId: String,
    templateId: String? = null,
  ): UiBuilderDocument =
    if (this == M3 && (templateId == null || templateId == defaultTemplateId)) {
      fixtureDocument().copy(id = designId, title = "Desktop workspace")
    } else {
      UiBuilderNewDesignSeed.document(
        designId = designId,
        catalogSystemId = systemId,
        templateId =
          (templateId ?: defaultTemplateId).also {
            require(it in templateIds) { "catalog '$systemId' has no template '$it'" }
          },
        catalogRevision = catalogRevision,
        nativeRuntimeId = nativeRuntimeId,
        fixture =
          Json.parseToJsonElement(resourceText("jetcaster-discover-operations-v1.json")).jsonObject,
        vocabulary = UiBuilderNewDesignSeed.Vocabulary.PACKAGED,
      )
    }

  /** The packaged capability used while a remote session loads its authoritative snapshot. */
  fun capabilityCatalog(): CapabilityCatalog =
    CapabilityCatalogParser.parse(resourceText(capabilitiesResource))

  companion object {
    fun forSystem(systemId: String): OfflineCatalog =
      entries.firstOrNull { it.systemId == systemId }
        ?: error("offline UI Builder has no packaged catalog '$systemId'")
  }
}

private fun resourceText(name: String): String =
  checkNotNull(object {}.javaClass.getResource("/$name")) { "missing desktop resource $name" }
    .readText()
