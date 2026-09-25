@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.NEW_DESIGN_ID
import ee.schimke.composeai.uibuilder.export.NEW_DESIGN_STATE_NAME
import ee.schimke.composeai.uibuilder.export.NewDesignNames
import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.NewDesignStateType
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradeMutationV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1

@Composable
private fun rememberNewDesignFormState(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
): NewDesignFormState =
  remember(catalogs, initialCatalogSystemId) {
    NewDesignFormState(catalogs, initialCatalogSystemId)
  }

/**
 * The New design form's fields, hoisted out of the dialog that used to own them.
 *
 * The form is asked for in two places now — the dialog the editor opens, and the panel the home
 * screen leads with — and two copies of nine interdependent fields is two forms that can disagree
 * about what a valid design id is. A holder rather than parameters and setters for the same reason:
 * every one of these fields is only meaningful next to the others.
 */
private class NewDesignFormState(
  val catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
) {
  private val initialCatalog =
    catalogs.firstOrNull { it.systemId == initialCatalogSystemId } ?: catalogs.first()

  var selectedCatalogId by mutableStateOf(initialCatalog.systemId)
  var selectedTemplateId by mutableStateOf(initialCatalog.templates.firstOrNull()?.id.orEmpty())
  // Pre-filled, so a design can be created in one click; a person who wants their own name
  // overwrites it, and one who wants another roll asks for it.
  var designId by mutableStateOf(NewDesignNames.random())
  var declared by mutableStateOf(listOf<NewDesignState>())
  // Folded away until asked for: most new designs declare no state at all, and the three
  // controls it takes to add one made the dialog read as a form with a required last section.
  var stateExpanded by mutableStateOf(false)
  var variableName by mutableStateOf("")
  var variableKind by mutableStateOf(NewDesignStateType.Flag)
  var variableInitial by mutableStateOf("")

  val selectedCatalog: UiBuilderNewDesignCatalog
    get() = catalogs.first { it.systemId == selectedCatalogId }

  val selectedTemplate: UiBuilderNewDesignTemplate
    get() =
      selectedCatalog.templates.firstOrNull { it.id == selectedTemplateId }
        ?: selectedCatalog.templates.first()

  val designIdValid: Boolean
    get() = designId.matches(NEW_DESIGN_ID)

  val variableNameValid: Boolean
    get() = NEW_DESIGN_STATE_NAME.matches(variableName) && declared.none { it.name == variableName }

  fun addVariable(): Boolean {
    if (!variableNameValid || !newDesignInitialValueValid(variableKind, variableInitial))
      return false
    declared += NewDesignState(variableName, variableKind, variableKind.parse(variableInitial))
    variableName = ""
    variableInitial = ""
    return true
  }
}

/** The New design form itself: catalog, starting point, id, and the optional state variables. */
@Composable
private fun NewDesignFormFields(form: NewDesignFormState, onSubmit: () -> Unit) {

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Catalog", style = MaterialTheme.typography.labelLarge)
    // In platform order — phone, watch, Remote Compose widget — and grouped under a platform
    // heading only where a platform has more than one catalog to choose between. With one
    // catalog per platform the chip already says which platform it is, and a heading over a
    // single chip would say it twice.
    val byPlatform = form.catalogs.groupBy { it.platform }.entries.sortedBy { it.key.ordinal }
    byPlatform.forEach { (platform, platformCatalogs) ->
      if (platformCatalogs.size > 1) {
        Text(
          platform.label,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        platformCatalogs.forEach { catalog ->
          FilterChip(
            selected = catalog.systemId == form.selectedCatalogId,
            onClick = {
              form.selectedCatalogId = catalog.systemId
              form.selectedTemplateId = catalog.templates.first().id
            },
            label = { Text(catalog.label) },
          )
        }
      }
    }
    Text("Starting point", style = MaterialTheme.typography.labelLarge)
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      form.selectedCatalog.templates.forEach { template ->
        FilterChip(
          selected = template.id == form.selectedTemplate.id,
          onClick = { form.selectedTemplateId = template.id },
          label = { Text(template.label) },
        )
      }
    }
    Text(
      form.selectedTemplate.supportingText,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    Text("Design ID", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
      value = form.designId,
      onValueChange = { form.designId = it },
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Design ID" },
      placeholder = { Text("my-widget") },
      trailingIcon = {
        TextButton(
          onClick = { form.designId = NewDesignNames.random() },
          modifier = Modifier.semantics { contentDescription = "Suggest another name" },
        ) {
          Text("Shuffle")
        }
      },
      supportingText = {
        Text(
          if (form.designId.isEmpty() || form.designIdValid) {
            "Letters, numbers, dots, underscores, and hyphens"
          } else {
            "Start with a letter or number and use only path-safe characters"
          }
        )
      },
      isError = form.designId.isNotEmpty() && !form.designIdValid,
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (form.designIdValid) onSubmit() }),
    )
    // Optional starting state. The Screen inspector can add and edit declarations later.
    if (!form.stateExpanded && form.declared.isEmpty()) {
      TextButton(
        onClick = { form.stateExpanded = true },
        modifier = Modifier.semantics { contentDescription = "Add state variables" },
      ) {
        Text("Add state variables…")
      }
    } else {
      Text("State", style = MaterialTheme.typography.labelLarge)
      Text(
        "Variables this screen reacts to. A property can be bound to one once the design exists.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NewDesignStateType.entries.forEach { kind ->
          FilterChip(
            selected = kind == form.variableKind,
            onClick = { form.variableKind = kind },
            label = { Text(kind.label) },
          )
        }
      }
      val initialValueValid = newDesignInitialValueValid(form.variableKind, form.variableInitial)
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = form.variableName,
          onValueChange = { form.variableName = it },
          modifier = Modifier.weight(1f).semantics { contentDescription = "State name" },
          placeholder = { Text("expanded") },
          isError = form.variableName.isNotEmpty() && !form.variableNameValid,
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { form.addVariable() }),
        )
        OutlinedTextField(
          value = form.variableInitial,
          onValueChange = { form.variableInitial = it },
          modifier = Modifier.weight(1f).semantics { contentDescription = "State initial value" },
          placeholder = { Text(form.variableKind.placeholder) },
          supportingText =
            if (!initialValueValid) {
              {
                Text(
                  when (form.variableKind) {
                    NewDesignStateType.Flag -> "Use true or false"
                    NewDesignStateType.Number -> "Use a whole number"
                    NewDesignStateType.Text -> ""
                  }
                )
              }
            } else null,
          isError = !initialValueValid,
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { form.addVariable() }),
        )
        TextButton(
          onClick = { form.addVariable() },
          enabled = form.variableNameValid && initialValueValid,
        ) {
          Text("Add")
        }
      }
      if (form.declared.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          form.declared.forEach { variable ->
            FilterChip(
              selected = false,
              onClick = { form.declared = form.declared - variable },
              label = { Text("${variable.name} · ${variable.type.label}") },
              trailingIcon = {
                Icon(
                  Icons.Filled.Close,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier =
                Modifier.semantics {
                  contentDescription = "Remove state variable ${variable.name}"
                },
            )
          }
        }
      }
    }
  }
}

/** Rejects typos in the form instead of silently turning them into `false` or `0`. */
internal fun newDesignInitialValueValid(type: NewDesignStateType, raw: String): Boolean =
  raw.isBlank() ||
    when (type) {
      NewDesignStateType.Flag -> raw.trim().toBooleanStrictOrNull() != null
      NewDesignStateType.Number -> raw.trim().toLongOrNull() != null
      NewDesignStateType.Text -> true
    }

@Composable
internal fun NewDesignDialog(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  onDismiss: (() -> Unit)?,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  val form = rememberNewDesignFormState(catalogs, initialCatalogSystemId)
  val submit = {
    onCreate(
      form.selectedCatalog.systemId,
      form.designId,
      form.selectedTemplate.id,
      form.declared,
    )
  }
  TrackEditorOverlay(true)
  AlertDialog(
    onDismissRequest = { onDismiss?.invoke() },
    title = { Text("Create a new design") },
    text = { NewDesignFormFields(form, submit) },
    confirmButton = { Button(onClick = submit, enabled = form.designIdValid) { Text("Create") } },
    dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * Why a design could not be opened, when the editor has nothing to draw instead.
 *
 * This exists because the alternative was a white page. The editor's body is guarded by `if
 * (loadedDocument != null && loadedCatalog != null)`, and that guard had no `else`: a refused open
 * left the composable emitting nothing at all, forever, while the reason sat in a status string
 * that is only read from inside the guarded branch. The server said `catalog unavailable for stored
 * design <id>`, the client parsed it, and the page showed white. The whole fix is having somewhere
 * to put the sentence the service already sent.
 *
 * [code] is the service's own, and decides the second paragraph. The distinction worth drawing is
 * whether the reader can do anything: a design pinned to a catalog source this deployment stopped
 * serving is an operator's problem, and telling that reader to try again wastes their time.
 */
@Composable
fun UiBuilderUnavailableScreen(
  designId: String,
  catalogSystemId: String,
  reason: String,
  code: ServiceErrorCodeV1?,
  recovery: UiBuilderCatalogRecoveryUi? = null,
  recoveryLoading: Boolean = false,
  recoveryError: String? = null,
) {
  MaterialTheme(colorScheme = EditorColors) {
    Box(
      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 560.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "This design could not be opened",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        // The service's own sentence, not a paraphrase. It names the condition precisely and a
        // rewrite here would be a second description to keep in step with the first.
        Text(
          text = reason,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
          text = unopenableDesignGuidance(code),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        if (code == ServiceErrorCodeV1.CATALOG_UNAVAILABLE) {
          Spacer(Modifier.height(20.dp))
          when {
            recoveryLoading ->
              Text(
                "Checking the current catalog…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            recovery != null -> CatalogRecoveryPanel(recovery)
            recoveryError != null ->
              Text(
                recoveryError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
              )
          }
        }
        Spacer(Modifier.height(20.dp))
        // Both ids, because the first question anyone asks about a page that will not open is
        // which design and which catalog, and the URL is not always what was typed.
        Text(
          text = "$catalogSystemId · $designId",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

data class UiBuilderCatalogRecoveryUi(
  val sourceRevision: String,
  val targetRevision: String,
  val changeCount: Int,
  val issues: List<String>,
  val canApply: Boolean,
  val loading: Boolean,
  val error: String?,
  val onApply: () -> Unit,
)

/** The exact, idempotent write corresponding to one READY recovery preview. */
internal fun CatalogUpgradePreviewV1.catalogRecoveryCommand(
  actorId: String,
  clientId: String,
): DesignCommandV1? {
  val targetHash = candidateDocumentHash ?: return null
  if (status != CatalogUpgradePreviewStatusV1.READY) return null
  return DesignCommandV1(
    designId = designId,
    operationId = "catalog-recovery:$previewDigest",
    actorId = actorId,
    clientId = clientId,
    baseRevision = baseRevision,
    operations =
      listOf(
        CatalogUpgradeMutationV1(
          sourceCatalogPin = sourceCatalogPin,
          targetCatalogPin = targetCatalogPin,
          sourceDocumentHash = sourceDocumentHash,
          targetDocumentHash = targetHash,
          previewDigest = previewDigest,
        )
      ),
  )
}

@Composable
private fun CatalogRecoveryPanel(recovery: UiBuilderCatalogRecoveryUi) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      "A compatible catalog update is available",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "${recovery.sourceRevision} → ${recovery.targetRevision} · " +
        "${recovery.changeCount} document changes",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    recovery.issues.take(5).forEach { issue ->
      Spacer(Modifier.height(4.dp))
      Text(
        issue,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = recovery.onApply, enabled = recovery.canApply && !recovery.loading) {
      Text(if (recovery.loading) "Re-pinning…" else "Re-pin and reopen")
    }
    recovery.error?.let {
      Spacer(Modifier.height(8.dp))
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/**
 * The one sentence that says whether the reader can act, keyed on the service's code.
 *
 * Separate from the composable so it can be asserted without rendering, and so the mapping lives in
 * one place rather than being spelled out per call site.
 */
internal fun unopenableDesignGuidance(code: ServiceErrorCodeV1?): String =
  when (code) {
    ServiceErrorCodeV1.CATALOG_UNAVAILABLE ->
      "This design is pinned to a catalog source this deployment no longer serves. If its content " +
        "validates against the current catalog, you can preview and confirm a new exact pin below."
    ServiceErrorCodeV1.NOT_FOUND ->
      "No design with this id exists here, or it is not one this account may open."
    ServiceErrorCodeV1.FORBIDDEN,
    ServiceErrorCodeV1.UNAUTHORIZED ->
      "This account may not open this design. Its owner can share it from the design's own share " +
        "page."
    ServiceErrorCodeV1.MIGRATION_REQUIRED ->
      "The stored design is in an older format this build will not read until it is migrated. That " +
        "is an operator step."
    ServiceErrorCodeV1.INTERNAL ->
      "The stored design could not be served. An operator can download and repair it, or retire it."
    else ->
      "Reloading the page may help. If it does not, an operator will need to look at the host."
  }

/**
 * One design on [UiBuilderNewDesignScreen], as the home screen needs it.
 *
 * Deliberately not the protocol's `DesignListItemV1`: this module draws screens and the home screen
 * needs four strings, so the host does the flattening — including turning an epoch millisecond into
 * whatever "yesterday" is in the reader's locale, which is a browser question.
 */
data class UiBuilderHomeDesign(
  val designId: String,
  val title: String,
  val catalogSystemId: String,
  /** Personal organization only; null means this design is at the top level. */
  val folder: String? = null,
  /** Already-formatted, e.g. `updated 3 days ago`. Empty renders nothing. */
  val updatedLabel: String = "",
  /** The revision the listing saw, so a thumbnail can be asked for at it; null draws none. */
  val revision: Long? = null,
)

/** One release's notes for the home screen's **What's new** panel, newest first. */
data class UiBuilderReleaseNote(val version: String, val date: String, val items: List<String>)

/**
 * The builder's **home page**: what `/ui-builder/` draws when no design is named.
 *
 * It used to be the New design dialog on an empty background, which made the front door of the
 * whole product a modal with one way through it: make something new. Anyone whose work was already
 * on the host — which, after the first day, is everyone — arrived at a create form and had no way
 * from here to the thing they were working on yesterday short of a URL they had to remember.
 *
 * So the page answers both questions a person actually arrives with. **Start something new** is the
 * same form as before, now a panel rather than a modal. **Your designs** is what is already here:
 * open one, or start a new design *from* one, which is the option that was missing entirely — most
 * designs begin as a variation of a design that exists, and the only way to have one was to build
 * it again by hand. [onBrowseDesigns] leads to the server-rendered index, which is where a design
 * is renamed, shared, deleted, and seen as a picture rather than a row.
 *
 * Every callback is nullable and the panel for it is simply absent when the host cannot do it: a
 * design kept in this browser has no server index to browse and nothing to copy on one.
 */
@Composable
fun UiBuilderNewDesignScreen(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  /** Every design this account may open, newest first. Empty hides the panel that lists them. */
  designs: List<UiBuilderHomeDesign> = emptyList(),
  /** Opens one in the editor, or null where the host cannot navigate. */
  onOpenDesign: ((designId: String) -> Unit)? = null,
  /** Starts a new design as a copy of an existing one, or null where the host cannot. */
  onCopyDesign: ((designId: String) -> Unit)? = null,
  /** Leaves for the host's full designs index, or null where there is none. */
  onBrowseDesigns: (() -> Unit)? = null,
  /** Moves a design into a personal folder. A null folder returns it to the top level. */
  onMoveDesign: ((designId: String, folder: String?) -> Unit)? = null,
  /** A design's picture at a revision, as the designs page shows it; null draws no pictures. */
  loadThumbnail: (suspend (designId: String, revision: Long) -> ImageBitmap?)? = null,
  /** What changed in the builder lately; empty hides the panel. */
  releaseNotes: List<UiBuilderReleaseNote> = EMBEDDED_RELEASE_NOTES,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  require(catalogs.isNotEmpty()) { "new design screen requires at least one catalog" }
  val form = rememberNewDesignFormState(catalogs, initialCatalogSystemId)
  MaterialTheme(colorScheme = EditorColors) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      BoxWithConstraints {
        // One breakpoint, and the same one the editor's own toolbar uses: side by side where both
        // panels are legible at once, stacked where a two-column layout would make each of them
        // too narrow to read the design titles in.
        val sideBySide = maxWidth >= 840.dp
        Column(
          modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Column(
            modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                "UI Builder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                "Start a design, or carry on with one you already have.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // The one-press way in, first: most people arriving here want a blank screen or the
            // smallest sample, and the full form below is for choosing a kind and a name.
            QuickStartStrip(form, onCreate)
            val newPanel: @Composable (Modifier) -> Unit = { modifier ->
              NewDesignHomePanel(modifier, form, onCreate)
            }
            val designsPanel: @Composable (Modifier) -> Unit = { modifier ->
              ExistingDesignsPanel(
                modifier = modifier,
                designs = designs,
                onOpenDesign = onOpenDesign,
                onCopyDesign = onCopyDesign,
                onBrowseDesigns = onBrowseDesigns,
                onMoveDesign = onMoveDesign,
                loadThumbnail = loadThumbnail,
              )
            }
            val notesPanel: @Composable (Modifier) -> Unit = { modifier ->
              if (releaseNotes.isNotEmpty()) WhatsNewPanel(modifier, releaseNotes)
            }
            val showDesigns = designs.isNotEmpty() || onBrowseDesigns != null
            if (sideBySide) {
              Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(
                  Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                  newPanel(Modifier.fillMaxWidth())
                  notesPanel(Modifier.fillMaxWidth())
                }
                if (showDesigns) designsPanel(Modifier.weight(1.2f))
              }
            } else {
              newPanel(Modifier.fillMaxWidth())
              if (showDesigns) designsPanel(Modifier.fillMaxWidth())
              notesPanel(Modifier.fillMaxWidth())
            }
          }
        }
      }
    }
  }
}

/** **Create from a template**: the detailed creation form. */
@Composable
private fun NewDesignHomePanel(
  modifier: Modifier,
  form: NewDesignFormState,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  val submit = {
    onCreate(
      form.selectedCatalog.systemId,
      form.designId,
      form.selectedTemplate.id,
      form.declared,
    )
  }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text("Create from a template", style = MaterialTheme.typography.titleMedium)
      Text(
        "Choose a kind of design, then give it a name.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text("Available kinds", style = MaterialTheme.typography.labelLarge)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        form.catalogs.forEach { catalog ->
          FilterChip(
            selected = form.selectedCatalogId == catalog.systemId,
            onClick = {
              form.selectedCatalogId = catalog.systemId
              form.selectedTemplateId = catalog.templates.first().id
            },
            label = { Text(catalog.label) },
          )
        }
        // These are intentionally named now, rather than hidden behind a generic blank Android
        // screen. They are the next Android template shapes, so a person knows what the chooser is
        // growing toward without being offered a button that cannot create the promised layout.
        FilterChip(
          selected = false,
          onClick = {},
          enabled = false,
          label = { Text("Adaptive app") },
        )
        FilterChip(
          selected = false,
          onClick = {},
          enabled = false,
          label = { Text("List-detail screen") },
        )
      }
      NewDesignFormFields(form, submit)
      Button(
        onClick = submit,
        enabled = form.designIdValid,
        modifier = Modifier.semantics { contentDescription = "Create design" },
      ) {
        Text("Create")
      }
    }
  }
}

/** **Open a file**: what is already on this host, and the things to do with one from here. */
@Composable
private fun ExistingDesignsPanel(
  modifier: Modifier,
  designs: List<UiBuilderHomeDesign>,
  onOpenDesign: ((designId: String) -> Unit)?,
  onCopyDesign: ((designId: String) -> Unit)?,
  onBrowseDesigns: (() -> Unit)?,
  onMoveDesign: ((designId: String, folder: String?) -> Unit)?,
  loadThumbnail: (suspend (designId: String, revision: Long) -> ImageBitmap?)? = null,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Recent designs", style = MaterialTheme.typography.titleMedium)
      Text(
        "The designs you or others changed most recently.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (designs.isEmpty()) {
        Text(
          "Nothing here yet. The first design you create will be listed here.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        // The most recent handful, not the lot: this is a way back into today's work, and the
        // full index — with its previews, its sharing and its delete — is one press away. Filed
        // designs sit under their folder, as they do in that index; until anything is filed the
        // list stays flat, because a "No folder" heading over everything says nothing.
        val recent = designs.take(HOME_DESIGN_LIMIT)
        val grouped = recent.any { it.folder != null }
        val folders = designs.mapNotNull { it.folder }.distinct().sorted()
        homeDesignFolders(recent).forEach { (folder, group) ->
          if (grouped) {
            Text(
              folder ?: "No folder",
              style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.semantics { heading() },
            )
          }
          group.forEach { design ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              if (loadThumbnail != null) DesignThumbnail(design, loadThumbnail)
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  design.title.ifBlank { design.designId },
                  style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                  listOf(design.designId, design.catalogSystemId, design.updatedLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (design.folder != null && !grouped) {
                  Text(
                    "Folder · ${design.folder}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  if (onOpenDesign != null) {
                    TextButton(
                      onClick = { onOpenDesign(design.designId) },
                      modifier =
                        Modifier.semantics { contentDescription = "Open ${design.designId}" },
                    ) {
                      Text("Open")
                    }
                  }
                  if (onCopyDesign != null) {
                    TextButton(
                      onClick = { onCopyDesign(design.designId) },
                      modifier =
                        Modifier.semantics { contentDescription = "Start from ${design.designId}" },
                    ) {
                      Text("Start from this")
                    }
                  }
                  if (onMoveDesign != null) {
                    FolderMoveMenu(
                      design = design,
                      folders = folders,
                      onMove = onMoveDesign,
                    )
                  }
                }
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          }
        }
      }
      if (onBrowseDesigns != null) {
        TextButton(
          onClick = onBrowseDesigns,
          modifier = Modifier.semantics { contentDescription = "All designs" },
        ) {
          Icon(Icons.Filled.FolderOpen, contentDescription = null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(
            if (designs.size > HOME_DESIGN_LIMIT) "All ${designs.size} designs" else "All designs"
          )
        }
      }
    }
  }
}

/**
 * One press to a new design: a button per starting point of the kind already selected — for an
 * Android app, the blank screen and the hello sample — named with the id the form generated.
 */
@Composable
private fun QuickStartStrip(
  form: NewDesignFormState,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "New ${form.selectedCatalog.label.lowercase()}",
        style = MaterialTheme.typography.titleSmall,
      )
      form.selectedCatalog.templates.take(QUICK_START_LIMIT).forEachIndexed { index, template ->
        val create = {
          onCreate(form.selectedCatalog.systemId, form.designId, template.id, emptyList())
        }
        val description = Modifier.semantics { contentDescription = "New from ${template.id}" }
        if (index == 0) {
          Button(onClick = create, enabled = form.designIdValid, modifier = description) {
            Text(template.label)
          }
        } else {
          FilledTonalButton(
            onClick = create,
            enabled = form.designIdValid,
            modifier = description,
          ) {
            Text(template.label)
          }
        }
      }
    }
  }
}

/**
 * The design as it looks now, from the same cached thumbnail its card on the designs page shows.
 */
@Composable
private fun DesignThumbnail(
  design: UiBuilderHomeDesign,
  loadThumbnail: suspend (designId: String, revision: Long) -> ImageBitmap?,
) {
  val revision = design.revision
  var picture by remember(design.designId, revision) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(design.designId, revision) {
    if (revision != null)
      picture = runCatching { loadThumbnail(design.designId, revision) }.getOrNull()
  }
  Box(
    Modifier.size(width = 64.dp, height = 96.dp)
      .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center,
  ) {
    picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
  }
}

/**
 * **What's new**: the builder's latest release notes, embedded from its changelog at build time.
 */
@Composable
private fun WhatsNewPanel(modifier: Modifier, notes: List<UiBuilderReleaseNote>) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("What's new", style = MaterialTheme.typography.titleMedium)
      notes.forEach { note ->
        Text(
          "${note.version} · ${note.date}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        note.items.forEach { item ->
          Text(
            "• ${item.replaceFirstChar(Char::uppercaseChar)}",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

/** Lets a person keep designs together without making the first folder mandatory. */
@Composable
private fun FolderMoveMenu(
  design: UiBuilderHomeDesign,
  folders: List<String>,
  onMove: (designId: String, folder: String?) -> Unit,
) {
  var expanded by remember(design.designId, design.folder) { mutableStateOf(false) }
  var newFolder by remember(design.designId) { mutableStateOf("") }
  Box {
    TextButton(
      onClick = { expanded = true },
      modifier = Modifier.semantics { contentDescription = "Move ${design.designId}" },
    ) {
      Text("Move")
    }
    TrackEditorOverlay(expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text("No folder") },
        onClick = {
          expanded = false
          onMove(design.designId, null)
        },
      )
      folders
        .filter { it != design.folder }
        .forEach { folder ->
          DropdownMenuItem(
            text = { Text(folder) },
            onClick = {
              expanded = false
              onMove(design.designId, folder)
            },
          )
        }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      OutlinedTextField(
        value = newFolder,
        onValueChange = { newFolder = it },
        modifier =
          Modifier.width(220.dp).padding(horizontal = 12.dp).semantics {
            contentDescription = "New folder"
          },
        label = { Text("New folder") },
        singleLine = true,
      )
      DropdownMenuItem(
        text = { Text("Create folder and move") },
        enabled = newFolder.trim().isNotEmpty(),
        onClick = {
          val folder = newFolder.trim()
          if (folder.isNotEmpty()) {
            expanded = false
            onMove(design.designId, folder)
          }
        },
      )
    }
  }
}

/** How many designs the home screen lists before deferring to the full index. */
private const val HOME_DESIGN_LIMIT = 6

/** Starting points offered as buttons above the form; the rest are one step further in. */
private const val QUICK_START_LIMIT = 3

/**
 * [designs] under their folders: folders in name order, ignoring case, and the unfiled last. Within
 * a folder the designs keep the order they came in, which is newest first.
 */
internal fun homeDesignFolders(
  designs: List<UiBuilderHomeDesign>
): List<Pair<String?, List<UiBuilderHomeDesign>>> =
  designs
    .groupBy { it.folder }
    .toList()
    .sortedWith(
      compareBy<Pair<String?, List<UiBuilderHomeDesign>>> { it.first == null }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.orEmpty() }
    )
