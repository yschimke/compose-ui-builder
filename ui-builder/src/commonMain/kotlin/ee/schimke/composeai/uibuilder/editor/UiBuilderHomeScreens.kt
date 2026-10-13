@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
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
import ee.schimke.composeai.uibuilder.runCatchingCancellable

@Composable
private fun rememberNewDesignFormState(
  catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  initialDesignId: String?,
  allowPublic: Boolean = false,
): NewDesignFormState =
  remember(catalogs, initialCatalogSystemId, initialDesignId, allowPublic) {
    NewDesignFormState(catalogs, initialCatalogSystemId, initialDesignId, allowPublic)
  }

/**
 * The New design form's fields, shared by the editor's dialog and the home screen panel so both
 * validate alike. A holder because the fields depend on each other.
 */
private class NewDesignFormState(
  val catalogs: List<UiBuilderNewDesignCatalog>,
  initialCatalogSystemId: String,
  initialDesignId: String?,
  val allowPublic: Boolean,
) {
  var publicRead by mutableStateOf(false)
  private val initialCatalog =
    catalogs.firstOrNull { it.systemId == initialCatalogSystemId } ?: catalogs.first()

  var otherTypesExpanded by mutableStateOf(!initialCatalog.isPrimary)
  var selectedCatalogId by mutableStateOf(initialCatalog.systemId)
  /**
   * The template group chosen as the target, when the picker offers a single catalog's groups
   * ([newDesignTargetGroups]); null while the choice is between catalogs.
   */
  var selectedGroup by
    mutableStateOf(
      initialCatalog.takeIf { catalogs.size == 1 }?.let(::newDesignTargetGroups)?.firstOrNull()
    )
  var selectedTemplateId by
    mutableStateOf(templatesForTarget(initialCatalog, selectedGroup).firstOrNull()?.id.orEmpty())
  // Pre-filled, so a design can be created in one click; a person who wants their own name
  // overwrites it, and one who wants another roll asks for it.
  var designId by mutableStateOf(initialDesignId ?: NewDesignNames.random())
  var declared by mutableStateOf(listOf<NewDesignState>())
  // Folded away until asked for: most new designs declare no state at all, and the three
  // controls it takes to add one made the dialog read as a form with a required last section.
  var stateExpanded by mutableStateOf(false)
  var variableName by mutableStateOf("")
  var variableKind by mutableStateOf(NewDesignStateType.Flag)
  var variableInitial by mutableStateOf("")

  val selectedCatalog: UiBuilderNewDesignCatalog
    get() = catalogs.first { it.systemId == selectedCatalogId }

  /** The starting points on offer for the chosen catalog and, when there is one, target group. */
  val targetTemplates: List<UiBuilderNewDesignTemplate>
    get() = templatesForTarget(selectedCatalog, selectedGroup).ifEmpty { selectedCatalog.templates }

  val selectedTemplate: UiBuilderNewDesignTemplate
    get() = targetTemplates.firstOrNull { it.id == selectedTemplateId } ?: targetTemplates.first()

  val designIdValid: Boolean
    get() = designId.matches(NEW_DESIGN_ID)

  val variableNameValid: Boolean
    get() = NEW_DESIGN_STATE_NAME.matches(variableName) && declared.none { it.name == variableName }

  fun selectCatalog(catalog: UiBuilderNewDesignCatalog) {
    if (selectedCatalogId == catalog.systemId) return
    selectedCatalogId = catalog.systemId
    selectedGroup = null
    selectedTemplateId = catalog.templates.first().id
  }

  fun select(choice: NewDesignTypeChoice) {
    val catalog = catalogs.first { it.systemId == choice.catalogSystemId }
    if (choice.group == null) return selectCatalog(catalog)
    if (selectedCatalogId == catalog.systemId && selectedGroup == choice.group) return
    selectedCatalogId = catalog.systemId
    selectedGroup = choice.group
    selectedTemplateId = templatesForTarget(catalog, choice.group).first().id
  }

  fun isSelected(choice: NewDesignTypeChoice): Boolean =
    selectedCatalogId == choice.catalogSystemId && selectedGroup == choice.group

  fun addVariable(): Boolean {
    if (!variableNameValid || !newDesignInitialValueValid(variableKind, variableInitial))
      return false
    declared += NewDesignState(variableName, variableKind, variableKind.parse(variableInitial))
    variableName = ""
    variableInitial = ""
    return true
  }
}

/**
 * "What are you designing?": the row of [NewDesignTypeOptions.primary] cards
 * ([newDesignTypeOptions] decides what they are), and any other catalogs behind "Other design
 * types…".
 */
@Composable
private fun NewDesignTypePicker(form: NewDesignFormState, picker: NewDesignTypeOptions) {
  val other = picker.other
  Text("1. What are you designing?", style = MaterialTheme.typography.titleSmall)
  // Three to a row: past that a card is too narrow for its title, so further cards wrap.
  picker.primary.chunked(TYPE_CARDS_PER_ROW).forEach { row ->
    Row(
      modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      row.forEach { choice ->
        val isSelected = form.isSelected(choice)
        Surface(
          modifier =
            Modifier.weight(1f)
              .fillMaxHeight()
              .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = { form.select(choice) },
              ),
          shape = RoundedCornerShape(12.dp),
          color =
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
          border =
            BorderStroke(
              1.dp,
              if (isSelected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(choice.title, style = MaterialTheme.typography.titleSmall)
            choice.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
      // Keep a short last row's cards the width of the full rows above it.
      if (picker.primary.size > TYPE_CARDS_PER_ROW)
        repeat(TYPE_CARDS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
    }
  }
  if (other.isNotEmpty()) {
    TextButton(onClick = { form.otherTypesExpanded = !form.otherTypesExpanded }) {
      Text(if (form.otherTypesExpanded) "Hide other design types" else "Other design types…")
    }
    if (form.otherTypesExpanded) {
      FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        other.forEach { catalog ->
          FilterChip(
            selected = form.selectedCatalogId == catalog.systemId,
            onClick = { form.selectCatalog(catalog) },
            label = { Text(catalog.label) },
          )
        }
      }
    }
  }
}

@Composable
private fun NewDesignChoice(
  selected: Boolean,
  label: String,
  supportingText: String,
  onClick: () -> Unit,
  preview: @Composable () -> Unit = {},
) {
  Surface(
    modifier =
      Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color =
      if (selected) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        1.dp,
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Row(
      Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      RadioButton(selected = selected, onClick = null)
      preview()
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (supportingText.isNotBlank())
          Text(supportingText, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/** The New design form itself: catalog, starting point, id, and the optional state variables. */
@Composable
private fun NewDesignFormFields(form: NewDesignFormState, onSubmit: () -> Unit) {

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val brand = LocalUiBuilderBrand.current
    val picker = remember(form.catalogs, brand) { newDesignTypeOptions(form.catalogs, brand) }
    // A host offering one catalog has no type to choose, so the form starts at the template —
    // unless that catalog's templates are grouped, when the groups are the choice.
    val oneCatalog = brand?.singleCatalog == true && form.catalogs.size == 1 && !picker.byGroup
    if (!oneCatalog) NewDesignTypePicker(form, picker)
    Text(
      if (oneCatalog) "Choose a starting point" else "2. Choose a starting point",
      style = MaterialTheme.typography.titleSmall,
    )
    Column(
      modifier = Modifier.selectableGroup(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val templates = form.targetTemplates
      // Headed only when the catalog offers more than one kind of thing, in the catalog's order —
      // and not when the picker already chose between those kinds.
      val headed =
        form.selectedGroup == null && templates.mapNotNull { it.group }.distinct().size > 1
      templates.forEachIndexed { index, template ->
        if (
          headed &&
            template.group != null &&
            template.group != templates.getOrNull(index - 1)?.group
        ) {
          Text(
            template.group,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp),
          )
        }
        key(form.selectedCatalogId, template.id) {
          NewDesignChoice(
            selected = template.id == form.selectedTemplate.id,
            label = template.label,
            supportingText = template.supportingText,
            onClick = { form.selectedTemplateId = template.id },
            preview = {
              TemplateThumbnail(template, form.selectedCatalog.previewCatalog) {
                form.selectedTemplateId = template.id
              }
            },
          )
        }
      }
    }
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
    if (form.allowPublic) {
      Text("Visibility", style = MaterialTheme.typography.labelLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = !form.publicRead,
          onClick = { form.publicRead = false },
          label = { Text("Private") },
        )
        FilterChip(
          selected = form.publicRead,
          onClick = { form.publicRead = true },
          label = { Text("Public (read only)") },
        )
      }
      Text(
        if (form.publicRead) "Anyone with the link can view. Only authorized editors can change it."
        else "Only you and invited collaborators. Change this later in Sharing.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
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
  /** The pre-filled name; null rolls a random one, which is what a person should see. */
  initialDesignId: String? = null,
  onCreatePublic: ((String, String, String, List<NewDesignState>) -> Unit)? = null,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  val form =
    rememberNewDesignFormState(
      catalogs,
      initialCatalogSystemId,
      initialDesignId,
      onCreatePublic != null,
    )
  val submit = {
    (if (form.publicRead) onCreatePublic ?: onCreate else onCreate)(
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
    text = {
      Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        NewDesignFormFields(form, submit)
      }
    },
    confirmButton = { Button(onClick = submit, enabled = form.designIdValid) { Text("Create") } },
    dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * Why a design could not be opened, shown when the editor has nothing to draw (otherwise the page
 * would be blank). [code] is the service's own and decides whether the reader can act or it is an
 * operator's problem.
 */
@Composable
fun UiBuilderUnavailableScreen(
  designId: String,
  catalogSystemId: String?,
  reason: String,
  code: ServiceErrorCodeV1?,
  recovery: UiBuilderCatalogRecoveryUi? = null,
  recoveryLoading: Boolean = false,
  recoveryError: String? = null,
) {
  MaterialTheme(colorScheme = EditorColors.branded(LocalUiBuilderBrand.current)) {
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
        // which design and which catalog, and the URL is not always what was typed. The catalog
        // only when it is known: a design that was never opened has told nobody its catalog.
        Text(
          text = catalogSystemId?.let { "$it · $designId" } ?: designId,
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
  return DesignCommandV1.Builder(
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
    .build()
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
 * The sentence saying whether the reader can act, keyed on the service's code. Separate so it is
 * testable without rendering.
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
 * One design on [UiBuilderNewDesignScreen]. Not the protocol's `DesignListItemV1`: the host
 * flattens it, including locale-aware relative times.
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

/**
 * One design kept in this browser rather than on the server, for the home screen's **In this
 * browser** panel.
 */
data class UiBuilderBrowserDesign(
  val designId: String,
  val title: String,
  val catalogSystemId: String,
  /** Already-formatted, e.g. `edited 29/09/2026, 08:41`. Empty renders nothing. */
  val updatedLabel: String = "",
  /** The server design this is a copy of, when it is one. */
  val copiedFrom: String? = null,
  /**
   * A record this builder cannot read (newer or damaged). Listed for download and deletion only.
   */
  val unreadable: Boolean = false,
)

/** One release's notes for the home screen's **What's new** panel, newest first. */
data class UiBuilderReleaseNote(val version: String, val date: String, val items: List<String>)

/**
 * The builder's **home page**, drawn at `/ui-builder/` when no design is named.
 *
 * **Start something new** is the create form as a panel; **Your designs** opens an existing design
 * or starts a new one from it. [onBrowseDesigns] leads to the server's full index. Every callback
 * is nullable, and its panel is absent when the host cannot do it.
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
  /**
   * The designs this browser keeps, newest first (its own and copies of read-only server designs).
   * Empty hides the panel.
   */
  browserDesigns: List<UiBuilderBrowserDesign> = emptyList(),
  /** Opens one of [browserDesigns] in the editor. */
  onOpenBrowserDesign: ((designId: String) -> Unit)? = null,
  /** Saves one of [browserDesigns] as a file: the only copy that outlives this browser's data. */
  onDownloadBrowserDesign: ((designId: String) -> Unit)? = null,
  /**
   * [onDownloadBrowserDesign]'s button label: "Download", or "Share" where the host uses a share
   * sheet.
   */
  browserDesignSaveLabel: String = "Download",
  /** Removes one of [browserDesigns] from this browser, after the person has confirmed it. */
  onDeleteBrowserDesign: ((designId: String) -> Unit)? = null,
  /** Why this browser may soon refuse to keep more, or null while there is room. */
  browserStorageNotice: String? = null,
  /**
   * A notice above the create panel with an action (e.g. "sign in to save designs here"), or null.
   * Only the host knows the account state.
   */
  accountNotice: String? = null,
  accountNoticeAction: EditorNoticeAction? = null,
  /**
   * A prompt to sign in shown in place of the create form, or null to offer the form. A
   * white-labelled host passes it to a signed-out visitor: its catalog's templates are seeded by
   * the server, so a design kept in the browser would start from none of them.
   */
  signInToCreate: EditorNoticeAction? = null,
  /** What changed in the builder lately; empty hides the panel. */
  releaseNotes: List<UiBuilderReleaseNote> = EMBEDDED_RELEASE_NOTES,
  /** The pre-filled design name; null rolls a random one, while previews pass a fixed name. */
  initialDesignId: String? = null,
  onCreatePublic: ((String, String, String, List<NewDesignState>) -> Unit)? = null,
  onCreate:
    (
      catalogSystemId: String,
      designId: String,
      templateId: String,
      state: List<NewDesignState>,
    ) -> Unit,
) {
  require(catalogs.isNotEmpty()) { "new design screen requires at least one catalog" }
  val form =
    rememberNewDesignFormState(
      catalogs,
      initialCatalogSystemId,
      initialDesignId,
      onCreatePublic != null,
    )
  val create = if (form.publicRead) onCreatePublic ?: onCreate else onCreate
  val brand = LocalUiBuilderBrand.current
  MaterialTheme(colorScheme = EditorColors.branded(brand)) {
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
              val openBrandHome = LocalUiBuilderOpenBrandHome.current
              Text(
                brand?.nameOr("UI Builder") ?: "UI Builder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier =
                  if (openBrandHome == null) Modifier
                  else Modifier.clickable(onClickLabel = "Home", onClick = openBrandHome),
              )
              Text(
                "Start a design, or carry on with one you already have.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (accountNotice != null) AccountNotice(accountNotice, accountNoticeAction)
            val newPanel: @Composable (Modifier) -> Unit = { modifier ->
              if (signInToCreate != null) SignInToCreatePanel(modifier, signInToCreate)
              else NewDesignHomePanel(modifier, form, create)
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
                catalogs = catalogs,
              )
            }
            val notesPanel: @Composable (Modifier) -> Unit = { modifier ->
              if (releaseNotes.isNotEmpty()) WhatsNewPanel(modifier, releaseNotes)
            }
            val showDesigns = designs.isNotEmpty() || onBrowseDesigns != null
            val showBrowserDesigns = browserDesigns.isNotEmpty()
            val browserPanel: @Composable (Modifier) -> Unit = { modifier ->
              BrowserDesignsPanel(
                modifier = modifier,
                designs = browserDesigns,
                onOpen = onOpenBrowserDesign,
                onDownload = onDownloadBrowserDesign,
                downloadLabel = browserDesignSaveLabel,
                onDelete = onDeleteBrowserDesign,
                storageNotice = browserStorageNotice,
              )
            }
            if (sideBySide) {
              Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(
                  Modifier.weight(1f),
                  verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                  newPanel(Modifier.fillMaxWidth())
                  notesPanel(Modifier.fillMaxWidth())
                }
                if (showDesigns || showBrowserDesigns) {
                  Column(
                    Modifier.weight(1.2f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                  ) {
                    if (showDesigns) designsPanel(Modifier.fillMaxWidth())
                    if (showBrowserDesigns) browserPanel(Modifier.fillMaxWidth())
                  }
                }
              }
            } else {
              newPanel(Modifier.fillMaxWidth())
              if (showDesigns) designsPanel(Modifier.fillMaxWidth())
              if (showBrowserDesigns) browserPanel(Modifier.fillMaxWidth())
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
      Text("New design", style = MaterialTheme.typography.titleMedium)
      Text(
        if (
          LocalUiBuilderBrand.current?.singleCatalog == true &&
            form.catalogs.size == 1 &&
            newDesignTargetGroups(form.catalogs.single()).isEmpty()
        )
          "Start blank or use a template."
        else "Choose a design type, then start blank or use a template.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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

/** **New design** for a visitor who must sign in first: why, and the way in. */
@Composable
private fun SignInToCreatePanel(modifier: Modifier, signIn: EditorNoticeAction) {
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
      Text("New design", style = MaterialTheme.typography.titleMedium)
      Text(
        "Sign in to start a design. Designs are kept on this server under your account, and " +
          "start from its templates.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Button(onClick = signIn.onClick) { Text(signIn.label) }
    }
  }
}

/** **Open a file**: what is already on this host, and the things to do with one from here. */
@Composable
internal fun ExistingDesignsPanel(
  modifier: Modifier,
  designs: List<UiBuilderHomeDesign>,
  onOpenDesign: ((designId: String) -> Unit)?,
  onCopyDesign: ((designId: String) -> Unit)?,
  onBrowseDesigns: (() -> Unit)?,
  onMoveDesign: ((designId: String, folder: String?) -> Unit)?,
  loadThumbnail: (suspend (designId: String, revision: Long) -> ImageBitmap?)? = null,
  /** The catalogs this page offers, which name each design's kind and say which are widgets. */
  catalogs: List<UiBuilderNewDesignCatalog> = emptyList(),
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
        // The most recent few, not the full index. Filed designs sit under their folder; until
        // anything is filed the list stays flat.
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
          BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 480.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              group.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  row.forEach { design ->
                    key(design.designId) {
                      ExistingDesignCard(
                        modifier = Modifier.weight(1f),
                        design = design,
                        folders = folders,
                        onOpenDesign = onOpenDesign,
                        onCopyDesign = onCopyDesign,
                        onMoveDesign = onMoveDesign,
                        loadThumbnail = loadThumbnail,
                        catalogs = catalogs,
                      )
                    }
                  }
                  if (row.size < columns) Spacer(Modifier.weight(1f))
                }
              }
            }
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

@Composable
private fun ExistingDesignCard(
  modifier: Modifier,
  design: UiBuilderHomeDesign,
  folders: List<String>,
  onOpenDesign: ((String) -> Unit)?,
  onCopyDesign: ((String) -> Unit)?,
  onMoveDesign: ((String, String?) -> Unit)?,
  loadThumbnail: (suspend (String, Long) -> ImageBitmap?)?,
  catalogs: List<UiBuilderNewDesignCatalog>,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (loadThumbnail != null) DesignThumbnail(design, loadThumbnail, onOpenDesign, catalogs)
      Text(design.title.ifBlank { design.designId }, style = MaterialTheme.typography.titleMedium)
      Text(
        listOf(homeDesignKindLabel(design.catalogSystemId, catalogs), design.updatedLabel)
          .filter { it.isNotBlank() }
          .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        design.designId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onOpenDesign != null) {
          Button(
            onClick = { onOpenDesign(design.designId) },
            modifier = Modifier.semantics { contentDescription = "Open ${design.designId}" },
          ) {
            Text("Open")
          }
        }
        if (onCopyDesign != null) {
          TextButton(
            onClick = { onCopyDesign(design.designId) },
            modifier = Modifier.semantics { contentDescription = "Start from ${design.designId}" },
          ) {
            Text("Duplicate")
          }
        }
        if (onMoveDesign != null) FolderMoveMenu(design, folders, onMoveDesign)
      }
    }
  }
}

/** A sentence about who this page is signed in as, and the one thing to do about it. */
@Composable
private fun AccountNotice(message: String, action: EditorNoticeAction?) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
  ) {
    FlowRow(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      if (action != null) {
        TextButton(onClick = action.onClick) { Text(action.label) }
      }
    }
  }
}

/**
 * **In this browser**: designs kept here, which go when the site data goes. **Download** is on
 * every row, and **Delete** confirms since there is no server copy.
 */
@Composable
private fun BrowserDesignsPanel(
  modifier: Modifier,
  designs: List<UiBuilderBrowserDesign>,
  onOpen: ((designId: String) -> Unit)?,
  onDownload: ((designId: String) -> Unit)?,
  downloadLabel: String,
  onDelete: ((designId: String) -> Unit)?,
  storageNotice: String?,
) {
  var confirmingDelete by remember { mutableStateOf<UiBuilderBrowserDesign?>(null) }
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
      Text("In this browser", style = MaterialTheme.typography.titleMedium)
      Text(
        "Kept only in this browser. Clearing its site data deletes them, so download any you want " +
          "to keep.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (storageNotice != null) {
        Text(
          storageNotice,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      designs.forEach { design ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(design.title.ifBlank { design.designId }, style = MaterialTheme.typography.bodyLarge)
          Text(
            listOfNotNull(
                design.designId,
                design.catalogSystemId.takeIf { it.isNotBlank() },
                design.copiedFrom?.let { "copy of $it" },
                design.updatedLabel.takeIf { it.isNotBlank() },
                "this builder cannot open it".takeIf { design.unreadable },
              )
              .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onOpen != null && !design.unreadable) {
              TextButton(
                onClick = { onOpen(design.designId) },
                modifier =
                  Modifier.semantics {
                    contentDescription = "Open ${design.designId} from browser"
                  },
              ) {
                Text("Open")
              }
            }
            if (onDownload != null) {
              TextButton(
                onClick = { onDownload(design.designId) },
                modifier =
                  Modifier.semantics { contentDescription = "$downloadLabel ${design.designId}" },
              ) {
                Text(downloadLabel)
              }
            }
            if (onDelete != null) {
              TextButton(
                onClick = { confirmingDelete = design },
                modifier =
                  Modifier.semantics {
                    contentDescription = "Delete ${design.designId} from this browser"
                  },
              ) {
                Text("Delete from this browser")
              }
            }
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }
  val pending = confirmingDelete
  if (pending != null && onDelete != null) {
    AlertDialog(
      onDismissRequest = { confirmingDelete = null },
      title = { Text("Delete ${pending.title.ifBlank { pending.designId }}?") },
      text = {
        Text(
          "This design is kept only in this browser, so deleting it cannot be undone. Download it " +
            "first to keep a copy." +
            (pending.copiedFrom?.let { " The original ($it) on the server is not affected." } ?: "")
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingDelete = null
            onDelete(pending.designId)
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") } },
    )
  }
}

/**
 * The design as it looks now, from the same cached thumbnail its card on the designs page shows.
 */
@Composable
private fun DesignThumbnail(
  design: UiBuilderHomeDesign,
  loadThumbnail: suspend (designId: String, revision: Long) -> ImageBitmap?,
  onOpenDesign: ((String) -> Unit)?,
  catalogs: List<UiBuilderNewDesignCatalog>,
) {
  val revision = design.revision
  var picture by remember(design.designId, revision) { mutableStateOf<ImageBitmap?>(null) }
  var loading by remember(design.designId, revision) { mutableStateOf(revision != null) }
  LaunchedEffect(design.designId, revision) {
    if (revision != null)
      picture = runCatchingCancellable { loadThumbnail(design.designId, revision) }.getOrNull()
    loading = false
  }
  val previewModifier =
    Modifier.fillMaxWidth()
      .height(200.dp)
      .then(
        if (onOpenDesign == null) Modifier
        else
          Modifier.clickable(
            onClickLabel = "Open ${design.title.ifBlank { design.designId }}",
            onClick = { onOpenDesign(design.designId) },
          )
      )
  DesignThumbnailFrame(previewModifier) {
    val image = picture
    if (image != null) {
      val isWidget = isWidgetDesign(design.catalogSystemId, catalogs)
      val ratio = image.width.toFloat() / image.height
      val imageModifier =
        if (isWidget)
          Modifier.padding(8.dp)
            .aspectRatio(ratio, matchHeightConstraintsFirst = ratio >= 1f)
            .clip(RoundedCornerShape(percent = 12))
        else Modifier.fillMaxSize().padding(8.dp)
      Image(
        image,
        contentDescription = "Preview of ${design.title.ifBlank { design.designId }}",
        contentScale = ContentScale.Fit,
        modifier = imageModifier,
      )
    } else
      Text(
        if (loading) "Loading preview…" else "Preview unavailable",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
}

@Composable
internal fun DesignThumbnailFrame(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
    content = content,
  )
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
    IconButton(
      onClick = { expanded = true },
      modifier = Modifier.semantics { contentDescription = "More actions for ${design.designId}" },
    ) {
      Icon(Icons.Filled.MoreVert, contentDescription = null)
    }
    TrackEditorOverlay(expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      Text(
        "Move to folder",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(12.dp),
      )
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

/**
 * [designs] grouped by folder: folders by case-insensitive name, unfiled last, keeping incoming
 * (newest-first) order within each.
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

private const val TYPE_CARDS_PER_ROW = 3
