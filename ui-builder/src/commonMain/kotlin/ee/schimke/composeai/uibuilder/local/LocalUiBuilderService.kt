package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.CapabilityDocumentWriteValidator
import ee.schimke.composeai.uibuilder.CapabilityPropertyWriteValidator
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.ConflictCode
import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.ConflictCodeV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.PresenceAcceptedResponseV1
import ee.schimke.composeai.uibuilder.protocol.RedoCommandV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.UndoCommandV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import ee.schimke.composeai.uibuilder.sha256Hex
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where the catalogs come from, so the service does not care whether that is a fetch or a key. */
fun interface LocalCatalogSource {
  /** @throws Exception when neither the network nor this browser can produce a catalog. */
  suspend fun catalogs(): List<CatalogCapabilityV1>
}

/**
 * The UI-builder service, in the page.
 *
 * It answers the same released v1 requests the server answers, from designs held in this browser's
 * storage, so the editor above it is the *same editor* — same protocol client, same drain loop,
 * same revision bookkeeping — talking to a service that happens to be a few frames down its own
 * call stack instead of across a socket. The alternative was a second editor host wired to the
 * reducer directly, which would have been a second implementation of every ordering rule the live
 * session already got right.
 *
 * What it deliberately does not answer: access control, catalog upgrades, deltas and exports. Those
 * are either meaningless for a design only this browser has (there is nobody to share it with) or
 * they are work the server does that a Wasm page cannot (rendering a PNG). Each is refused by name
 * rather than by silence — see [unsupported].
 *
 * Catalogs are the one thing it cannot make up. A design is meaningless without the component
 * capabilities it is pinned to, so those come from [catalogs], which the browser backs with a
 * network-first, storage-second source: online, the catalog is fetched and remembered; offline, the
 * remembered one opens the design.
 */
class LocalUiBuilderService(
  private val store: LocalDesignStore,
  private val catalogs: LocalCatalogSource,
  private val clock: () -> Long = { 0 },
  private val compactionThresholdBytes: Int = LocalDesignSession.DEFAULT_COMPACTION_THRESHOLD_BYTES,
) {
  private val sessions = mutableMapOf<String, LocalDesignSession>()

  /** The last persistence answer, so the editor can say when this browser stopped keeping up. */
  var lastPersistence: LocalPersistence = LocalPersistence.Stored
    private set

  suspend fun execute(request: UiBuilderRequestV1): UiBuilderResponseV1 =
    when (request) {
      is ListCatalogsRequestV1 ->
        try {
          CatalogsResponseV1(catalogs.catalogs())
        } catch (failure: Exception) {
          serviceError(
            ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
            "no catalog is available offline: ${failure.message ?: "the browser has not stored one"}",
          )
        }
      is OpenDesignRequestV1 -> snapshot(request.designId)
      is GetSnapshotRequestV1 -> snapshot(request.designId)
      is CreateDesignRequestV1 -> create(request.document.toUiBuilderDocument())
      is ApplyOperationRequestV1 -> apply(request)
      // Answered rather than refused so the editor's heartbeat is harmless in this mode. There is
      // one actor in a design only this browser holds, and it is the one asking.
      is UpdatePresenceRequestV1 ->
        PresenceAcceptedResponseV1(request.designId, request.presence.actorId)
      else -> unsupported(request::class.simpleName ?: "request")
    }

  /**
   * Creates a design this browser did not already hold.
   *
   * Refuses one it does, for the reason the server's `PUT` refuses it: create is not replace, and a
   * "New design" that silently overwrote a design of the same name would lose work with no undo.
   */
  suspend fun create(document: UiBuilderDocument): UiBuilderResponseV1 {
    val catalogSystemId =
      document.catalogPin.systemId()
        ?: return serviceError(
          ServiceErrorCodeV1.BAD_REQUEST,
          "the document names no catalog to pin to",
        )
    if (store.read(document.id) != null) {
      return serviceError(
        ServiceErrorCodeV1.BAD_REQUEST,
        "this browser already holds a design called ${document.id}",
      )
    }
    val record =
      localDesignRecord(document, catalogSystemId, sequence = 0, nowEpochMillis = clock())
    return try {
      store.write(record)
      sessions.remove(document.id)
      SnapshotResponseV1(
        ServiceSnapshotV1(
          designId = document.id,
          state = DesignStateV1(lastSequence = 0, document = document.toDesignDocumentV1()),
          catalog = catalogFor(catalogSystemId) ?: return catalogUnavailable(catalogSystemId),
          retainedFromSequence = 0,
        )
      )
    } catch (failure: LocalDesignStorageException) {
      serviceError(
        ServiceErrorCodeV1.INTERNAL,
        failure.message ?: "this browser refused to store the design",
      )
    }
  }

  private suspend fun snapshot(designId: String): UiBuilderResponseV1 {
    val session =
      openSession(designId)
        ?: return serviceError(
          ServiceErrorCodeV1.NOT_FOUND,
          "this browser holds no design called $designId",
        )
    val catalog =
      catalogFor(session.catalogSystemId) ?: return catalogUnavailable(session.catalogSystemId)
    return SnapshotResponseV1(
      ServiceSnapshotV1(
        designId = designId,
        state =
          DesignStateV1(
            lastSequence = session.sequence,
            document = session.document.toDesignDocumentV1(),
          ),
        catalog = catalog,
        // Everything this browser holds is retained: the log is the design, and there is no window
        // to fall out of.
        retainedFromSequence = 0,
      )
    )
  }

  private suspend fun apply(request: ApplyOperationRequestV1): UiBuilderResponseV1 {
    val designId = request.submission.designId()
    val session =
      openSession(designId)
        ?: return serviceError(
          ServiceErrorCodeV1.NOT_FOUND,
          "this browser holds no design called $designId",
        )
    val mapping = request.submission.toLocalSubmission()
    if (mapping is LocalSubmissionMapping.Rejected) {
      return serviceError(ServiceErrorCodeV1.BAD_REQUEST, mapping.message)
    }
    val result = session.submit((mapping as LocalSubmissionMapping.Mapped).record)
    lastPersistence = result.persistence
    return OperationOutcomeResponseV1(
      when (val outcome = result.outcome) {
        is CommandOutcome.Accepted ->
          AcceptedOutcomeV1(
            operationId = request.submission.operationId(),
            committedRevision = outcome.committedRevision.toLong(),
            sequence = result.sequence,
            documentHash = sha256Hex(outcome.canonicalDocument),
            idempotentReplay = outcome.idempotentReplay,
            conflicts =
              outcome.conflicts.map {
                CommandConflictV1(
                  code =
                    when (it.code) {
                      ConflictCode.STALE_PROPERTY_WRITE -> ConflictCodeV1.STALE_PROPERTY_WRITE
                      ConflictCode.STALE_MOVE -> ConflictCodeV1.STALE_MOVE
                    },
                  nodeId = it.nodeId,
                  field = it.field,
                  overwrittenRevision = it.overwrittenRevision.toLong(),
                )
              },
            documentUpdatedAtEpochMillis = null,
          )
        is CommandOutcome.Rejected ->
          RejectedOutcomeV1(
            operationId = request.submission.operationId(),
            currentRevision = session.document.revision.toLong(),
            code =
              RejectionCodeV1.entries.firstOrNull { it.name == outcome.code.name }
                ?: RejectionCodeV1.INVALID_COMMAND,
            message = outcome.message,
            operationIndex = outcome.operationIndex,
            nodeId = outcome.nodeId,
            field = outcome.field,
          )
      }
    )
  }

  /**
   * The open session for [designId], replayed from storage the first time it is asked for.
   *
   * Kept for the life of the page: replaying a log is cheap once and wasteful on every keystroke,
   * and the reducer state it produces — tombstones, the compensation history undo reads — is what
   * makes a second edit behave like the first.
   */
  private suspend fun openSession(designId: String): LocalDesignSession? {
    sessions[designId]?.let {
      return it
    }
    val record = store.read(designId) ?: return null
    val session =
      LocalDesignSession.open(
        record = record,
        store = store,
        validators = validatorsFor(record.catalogSystemId),
        clock = clock,
        compactionThresholdBytes = compactionThresholdBytes,
      )
    sessions[designId] = session
    return session
  }

  /**
   * The same catalog rules the server admits a mutation against, or none when the catalog is not
   * available.
   *
   * "None" is the honest state rather than a refusal to open: the editor validates every edit
   * against the catalog it is drawing with before it submits one, so an offline session whose
   * catalog could not be loaded has already failed to open a canvas — there is nothing left for a
   * second validator to protect.
   */
  private suspend fun validatorsFor(catalogSystemId: String): LocalDesignValidators {
    val capability = catalogFor(catalogSystemId) ?: return LocalDesignValidators()
    val validator =
      CapabilityValidator(
        CapabilityCatalogParser.parse(
          catalogJson.encodeToJsonElement(CatalogCapabilityV1.serializer(), capability)
        )
      )
    return LocalDesignValidators(
      property = CapabilityPropertyWriteValidator(validator),
      document = CapabilityDocumentWriteValidator(validator),
    )
  }

  private suspend fun catalogFor(catalogSystemId: String): CatalogCapabilityV1? =
    try {
      catalogs.catalogs().firstOrNull { it.benchmark.catalogSystemId == catalogSystemId }
    } catch (_: Exception) {
      null
    }

  private fun catalogUnavailable(catalogSystemId: String): ErrorResponseV1 =
    serviceError(
      ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
      "this browser has not stored the $catalogSystemId catalog, so the design cannot be opened offline",
    )

  private fun unsupported(what: String): ErrorResponseV1 =
    serviceError(
      ServiceErrorCodeV1.BAD_REQUEST,
      "$what is not answered by a browser-local design session",
    )

  private fun serviceError(code: ServiceErrorCodeV1, message: String): ErrorResponseV1 =
    ErrorResponseV1(ServiceErrorV1(code, message))

  private companion object {
    val catalogJson = Json {
      encodeDefaults = true
      explicitNulls = false
    }
  }
}

/**
 * The catalog a candidate document is pinned to.
 *
 * `catalogPin` is carried untyped on [UiBuilderDocument] — the renderer model keeps the protocol's
 * own shapes as JSON rather than restating them — so the one field this service needs is read out
 * rather than the whole pin being decoded.
 */
private fun JsonObject.systemId(): String? = (this["systemId"] as? JsonPrimitive)?.contentOrNull

private fun DesignSubmissionV1.designId(): String =
  when (this) {
    is DesignCommandV1 -> designId
    is UndoCommandV1 -> designId
    is RedoCommandV1 -> designId
  }

private fun DesignSubmissionV1.operationId(): String =
  when (this) {
    is DesignCommandV1 -> operationId
    is UndoCommandV1 -> operationId
    is RedoCommandV1 -> operationId
  }
