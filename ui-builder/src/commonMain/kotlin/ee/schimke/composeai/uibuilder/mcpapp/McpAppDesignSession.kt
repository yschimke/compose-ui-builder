package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.OpenedUidDesign
import ee.schimke.composeai.uibuilder.UidDesignCollection
import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the MCP App host shows: the design, whether it is saved, and anything the person must
 * decide.
 *
 * [generation] moves only when the file is (re)loaded — a first open, a reload after a conflict, an
 * external edit adopted. The editor is keyed on it, so a reload starts a fresh undo history and a
 * save does not: undo stays in memory across saves, as the local store keeps it, while the file
 * holds only the document.
 */
data class McpAppDesignState(
  val file: McpAppFile,
  val document: UiBuilderDocument? = null,
  val generation: Int = 0,
  val etag: String? = null,
  val writable: Boolean = false,
  val dirty: Boolean = false,
  val saving: Boolean = false,
  /** Whether `resources/subscribe` was accepted, i.e. external edits will be seen. */
  val live: Boolean = false,
  val notice: McpAppNotice? = null,
  /** Set when there is no design to show at all: the first read or parse failed. */
  val failure: String? = null,
  /** The file's top-level designs when it holds several; [document] is the active one. */
  val designs: UidDesignCollection? = null,
)

/** Something the person has to see, and for some of them decide. */
sealed interface McpAppNotice {
  /** The host did not grant `writable: true`. Edits stay in this view; the file is not written. */
  data object ReadOnly : McpAppNotice

  /**
   * A save found a newer file. Offer [McpAppDesignSession.reload] or
   * [McpAppDesignSession.overwrite].
   */
  data class Conflict(val currentEtag: String) : McpAppNotice

  /** The file changed outside while there were unsaved edits. Offer reload, or keep editing. */
  data class ExternalChange(val etag: String?) : McpAppNotice

  /** The host refused the write as too large. */
  data class TooLarge(val maxBytes: Long, val bytes: Int) : McpAppNotice

  /** A read or write failed, or an external edit could not be parsed. The design stays as it is. */
  data class Error(val message: String) : McpAppNotice
}

/**
 * One opened `.uid` file, edited in an MCP App host.
 *
 * The host owns the file; this owns the rules for keeping the editor and the file honest with each
 * other, through an [McpAppBridge]:
 * - **load** reads the host's `resourceUri` as text and parses `DesignDocumentV1`;
 * - **save** writes the whole document with `ifMatch` set to the last etag, and never when the host
 *   did not say `writable: true`, or when nothing changed — opening and closing a design writes
 *   nothing;
 * - **conflict** keeps the local design and asks: reload (discard) or overwrite (write again naming
 *   the etag the host reported);
 * - **external change** (`notifications/resources/updated`) reloads a clean design and asks about a
 *   dirty one; the echo of this editor's own save is recognised by its etag and ignored.
 *
 * Operations are serialized, because the browser calls them from UI events and a notification can
 * land while a save is waiting on the host.
 */
class McpAppDesignSession(
  private val bridge: McpAppBridge,
  val file: McpAppFile,
  private val onStateChanged: (McpAppDesignState) -> Unit = {},
) {
  private val lock = Mutex()

  var state: McpAppDesignState = McpAppDesignState(file)
    private set(value) {
      field = value
      onStateChanged(value)
    }

  /** The document as last read or written: what "dirty" is measured against. */
  private var savedDocument: UiBuilderDocument? = null
  private var openedFile: OpenedUidDesign? = null

  /** The document the editor currently shows. */
  private var current: UiBuilderDocument? = null

  private var lastContext: McpAppModelContext? = null

  /** Subscribes for external edits, then reads the file. */
  suspend fun open() = lock.withLock {
    // Best effort: without it the design still opens, it just does not follow external edits,
    // which [McpAppDesignState.live] tells the person.
    val live = runCatching { bridge.subscribe(file.resourceUri) }.isSuccess
    state = state.copy(live = live)
    val contents = runCatching {
      bridge.read(file.resourceUri)
    }
      .getOrElse {
        state = state.copy(failure = "Could not read ${file.name}: ${it.message}")
        return@withLock
      }
    adopt(contents, initial = true)
  }

  /**
   * The editor reported its document: after an edit, an undo, or a state change that is not an edit
   * at all (a selection, a panel). Returns whether it is a different document from the last one
   * reported, which is what a host debounces autosave on.
   */
  fun edited(document: UiBuilderDocument): Boolean {
    if (document === current) return false
    current = document
    val dirty = document != savedDocument
    if (dirty != state.dirty) state = state.copy(dirty = dirty)
    return true
  }

  /** Writes the current design if it has unsaved changes and the host allows writes. */
  suspend fun save() = lock.withLock { writeLocked(ifMatch = state.etag) }

  /** After a [McpAppNotice.Conflict]: write anyway, replacing the version the host reported. */
  suspend fun overwrite() = lock.withLock {
    val conflict = state.notice as? McpAppNotice.Conflict ?: return@withLock
    writeLocked(ifMatch = conflict.currentEtag, force = true)
  }

  /**
   * Makes [id] the file's active design: writes the file with it active — carrying any unsaved edit
   * to the design that was active — and opens it. Refused, as a save is, by a read-only file or an
   * unresolved conflict.
   */
  suspend fun selectDesign(id: String) = lock.withLock {
    val opened = openedFile ?: return@withLock
    if (opened.collection == null || opened.document.id == id) return@withLock
    if (!state.writable) {
      state = state.copy(notice = McpAppNotice.ReadOnly)
      return@withLock
    }
    if (state.notice is McpAppNotice.Conflict) return@withLock
    val text = runCatching {
      opened.switchTo(id, current ?: opened.document)
    }
      .getOrElse {
        state = state.copy(notice = McpAppNotice.Error("Not switched: ${it.message}"))
        return@withLock
      }
    state = state.copy(saving = true)
    val outcome = runCatching {
      bridge.write(file.resourceUri, text, state.etag)
    }
      .getOrElse {
        state =
          state.copy(saving = false, notice = McpAppNotice.Error("Not switched: ${it.message}"))
        return@withLock
      }
    state = state.copy(saving = false)
    when (outcome) {
      is McpAppWriteOutcome.Saved ->
        adopt(
          McpAppFileContents(text, outcome.etag.ifEmpty { null }, writable = true),
          initial = false,
        )
      is McpAppWriteOutcome.Conflict ->
        state = state.copy(notice = McpAppNotice.Conflict(outcome.etag))
      is McpAppWriteOutcome.TooLarge ->
        state =
          state.copy(
            notice = McpAppNotice.TooLarge(outcome.maxBytes, text.encodeToByteArray().size)
          )
    }
  }

  /** Discards local changes and reads the file again. */
  suspend fun reload() = lock.withLock {
    val contents = runCatching {
      bridge.read(file.resourceUri)
    }
      .getOrElse {
        state = state.copy(notice = McpAppNotice.Error("Could not reload: ${it.message}"))
        return@withLock
      }
    adopt(contents, initial = false)
  }

  /** After an [McpAppNotice.ExternalChange]: keep the local edits; the next save will conflict. */
  fun keepLocalChanges() {
    if (state.notice is McpAppNotice.ExternalChange) state = state.copy(notice = null)
  }

  /** Dismisses an informational notice. Read-only is not dismissable: it is the file's state. */
  fun dismissNotice() {
    when (state.notice) {
      is McpAppNotice.Error,
      is McpAppNotice.TooLarge -> state = state.copy(notice = null)
      else -> Unit
    }
  }

  /** `notifications/resources/updated` for [uri]. */
  suspend fun resourceUpdated(uri: String) = lock.withLock {
    if (uri != file.resourceUri) return@withLock
    val contents = runCatching {
      bridge.read(file.resourceUri)
    }
      .getOrElse {
        state =
          state.copy(
            notice = McpAppNotice.Error("Could not read an external change: ${it.message}")
          )
        return@withLock
      }
    if (isSameVersion(contents)) return@withLock
    if (state.dirty) {
      state = state.copy(notice = McpAppNotice.ExternalChange(contents.etag))
    } else {
      adopt(contents, initial = false)
    }
  }

  /** Tells the model which layer is selected; a repeat of the last selection sends nothing. */
  suspend fun select(nodeId: String?) {
    val document = current ?: return
    val context = McpAppSelectionContext.of(file, document, nodeId)
    if (context == lastContext) return
    lastContext = context
    runCatching { bridge.updateModelContext(context) }
  }

  /**
   * A comment on [nodeId], from the node's menu: the model context first — the node's picture and
   * detail — then one `ui/message` with the words and the node's titled block. In that order
   * because a message sent at once starts a turn, and the turn reads the context attached when it
   * starts; the other order would hand the picture to the turn after.
   *
   * Blank text, or a node the design no longer has, sends nothing and returns false.
   */
  suspend fun comment(nodeId: String, text: String, image: McpAppImage? = null): Boolean {
    val document = current ?: return false
    val message = McpAppNodeComment.message(file, document, nodeId, text) ?: return false
    val context = McpAppNodeComment.context(file, document, nodeId, text, image) ?: return false
    // The comment's context replaces the selection's. Remembered as the last one sent, so the next
    // selection the editor reports replaces it in turn rather than being mistaken for a repeat.
    lastContext = context
    runCatching { bridge.updateModelContext(context) }
    return runCatching { bridge.sendMessage(message) }
      .onFailure {
        state = state.copy(notice = McpAppNotice.Error("Comment not sent: ${it.message}"))
      }
      .isSuccess
  }

  /** Stops following the file, as the host tears the app down. */
  suspend fun close() {
    if (state.live) runCatching { bridge.unsubscribe(file.resourceUri) }
  }

  private fun isSameVersion(contents: McpAppFileContents): Boolean {
    val etag = contents.etag
    if (etag != null && etag == state.etag) return true
    // A host without etags: compare what the file says with what it said last.
    if (etag == null && state.etag == null) {
      val saved = savedDocument ?: return false
      return runCatching {
          val read = UidDesignFiles.open(contents.text)
          read.document == saved && read.production == openedFile?.production
        }
        .getOrDefault(false)
    }
    return false
  }

  private fun adopt(contents: McpAppFileContents, initial: Boolean) {
    val opened = runCatching {
      UidDesignFiles.open(contents.text)
    }
      .getOrElse {
        val message = "${file.name} is not a UI Builder design: ${it.message}"
        state =
          if (initial || state.document == null) state.copy(failure = message)
          else state.copy(notice = McpAppNotice.Error(message))
        return
      }
    openedFile = opened
    val document = opened.document
    savedDocument = document
    current = document
    lastContext = null
    state =
      state.copy(
        document = document,
        generation = state.generation + 1,
        etag = contents.etag,
        writable = contents.writable,
        dirty = false,
        failure = null,
        notice = if (contents.writable) null else McpAppNotice.ReadOnly,
        designs = opened.collection,
      )
  }

  private suspend fun writeLocked(ifMatch: String?, force: Boolean = false) {
    if (!state.writable) {
      state = state.copy(notice = McpAppNotice.ReadOnly)
      return
    }
    val document = current ?: return
    if (!force && document == savedDocument) return
    // A conflict is resolved by a person, not by the next autosave writing over it.
    if (!force && state.notice is McpAppNotice.Conflict) return
    val text = runCatching {
      requireNotNull(openedFile).encode(document)
    }
      .getOrElse {
        state = state.copy(notice = McpAppNotice.Error("Not saved: ${it.message}"))
        return
      }
    state = state.copy(saving = true)
    val outcome = runCatching {
      bridge.write(file.resourceUri, text, ifMatch)
    }
      .getOrElse {
        state = state.copy(saving = false, notice = McpAppNotice.Error("Not saved: ${it.message}"))
        return
      }
    state =
      when (outcome) {
        is McpAppWriteOutcome.Saved -> {
          savedDocument = document
          openedFile = UidDesignFiles.open(text)
          state.copy(
            designs = openedFile?.collection,
            saving = false,
            etag = outcome.etag.ifEmpty { null },
            // An edit made while the write was in flight is still unsaved.
            dirty = current != document,
            notice = null,
          )
        }
        is McpAppWriteOutcome.Conflict ->
          state.copy(saving = false, notice = McpAppNotice.Conflict(outcome.etag))
        is McpAppWriteOutcome.TooLarge ->
          state.copy(
            saving = false,
            notice = McpAppNotice.TooLarge(outcome.maxBytes, text.encodeToByteArray().size),
          )
      }
  }
}
