package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

private enum class StateEditorKind(val label: String, val wire: String) {
  Flag("Flag", "bool"),
  Number("Number", "int"),
  Decimal("Decimal", "float"),
  Text("Text", "string");

  fun parse(value: String): JsonPrimitive? =
    when (this) {
      Flag -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive)
      Number -> value.toIntOrNull()?.let(::JsonPrimitive)
      Decimal -> value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
      Text -> JsonPrimitive(value)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StateVariablesInspector(
  document: UiBuilderDocument,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<String?>(null) }
  var name by remember { mutableStateOf("") }
  var kind by remember { mutableStateOf(StateEditorKind.Flag) }
  var initial by remember { mutableStateOf("false") }
  var nullable by remember { mutableStateOf(false) }
  var startEmpty by remember { mutableStateOf(false) }
  fun reset() {
    editing = null
    name = ""
    initial = "false"
    kind = StateEditorKind.Flag
    nullable = false
    startEmpty = false
  }
  LaunchedEffect(document.stateVariables) {
    if (editing == null && name.isNotEmpty() && name in document.stateVariables) editing = name
  }
  TextButton(onClick = { expanded = !expanded }) { Text("State · ${document.stateVariables.size}") }
  if (!expanded) return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      "Values your screen reacts to. Bind them from a property, or change them with an action.",
      style = MaterialTheme.typography.bodySmall,
    )
    document.stateVariables.forEach { (key, encoded) ->
      val declaration = encoded as? JsonObject ?: return@forEach
      Row(Modifier.fillMaxWidth()) {
        TextButton(
          onClick = {
            editing = key
            name = key
            val value = declaration["initialValue"]
            initial = (value as? JsonPrimitive)?.contentOrNull ?: "null"
            kind =
              StateEditorKind.entries.firstOrNull {
                it.wire == (declaration["valueType"] as? JsonPrimitive)?.content
              }
                ?: when {
                  (value as? JsonPrimitive)?.isString == true -> StateEditorKind.Text
                  (value as? JsonPrimitive)?.booleanOrNull != null -> StateEditorKind.Flag
                  else -> StateEditorKind.Number
                }
            startEmpty = value is JsonNull
            nullable =
              (declaration["nullable"] as? JsonPrimitive)?.booleanOrNull ?: (value is JsonNull)
          },
          modifier = Modifier.weight(1f).semantics { contentDescription = "Edit state $key" },
        ) {
          Text("$key = ${declaration["initialValue"]}")
        }
        TextButton(
          onClick = { dispatch(UiBuilderEditorEvent.RemoveStateVariable(key)) },
          modifier = Modifier.semantics { contentDescription = "Remove state $key" },
        ) {
          Text("Remove")
        }
      }
    }
    Text(
      if (editing == null) "Add state" else "Edit $editing",
      style = MaterialTheme.typography.labelLarge,
    )
    OutlinedTextField(
      name,
      { name = it },
      Modifier.fillMaxWidth().onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
      enabled = editing == null,
      label = { Text("State name") },
      singleLine = true,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      StateEditorKind.entries.forEach { choice ->
        FilterChip(
          selected = kind == choice,
          onClick = {
            kind = choice
            initial =
              when (choice) {
                StateEditorKind.Flag -> "false"
                StateEditorKind.Text -> ""
                else -> "0"
              }
          },
          label = { Text(choice.label) },
        )
      }
    }
    val parsed = if (nullable && startEmpty) JsonNull else kind.parse(initial)
    OutlinedTextField(
      initial,
      { initial = it },
      Modifier.fillMaxWidth().onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
      enabled = !startEmpty,
      label = { Text("Initial value") },
      isError = parsed == null,
      singleLine = true,
    )
    Row {
      Checkbox(
        nullable,
        {
          nullable = it
          if (!it) startEmpty = false
        },
      )
      Text("Allow empty (null)", Modifier.padding(top = 12.dp))
    }
    if (nullable)
      Row {
        Checkbox(startEmpty, { startEmpty = it })
        Text("Start empty", Modifier.padding(top = 12.dp))
      }
    val validName =
      NEW_DESIGN_STATE_NAME.matches(name) && (editing != null || name !in document.stateVariables)
    Row {
      TextButton(
        enabled = validName && parsed != null,
        onClick = {
          val previous = document.stateVariables[name] as? JsonObject ?: JsonObject(emptyMap())
          val declaration =
            JsonObject(
              previous +
                mapOf(
                  "type" to JsonPrimitive(if (kind == StateEditorKind.Text) "text" else "value"),
                  "valueType" to JsonPrimitive(kind.wire),
                  "nullable" to JsonPrimitive(nullable),
                  "initialValue" to requireNotNull(parsed),
                  "persistence" to (previous["persistence"] ?: JsonPrimitive("preview")),
                )
            )
          dispatch(UiBuilderEditorEvent.SetStateVariable(name, declaration))
        },
        modifier = Modifier.semantics { contentDescription = "Save state variable" },
      ) {
        Text(if (editing == null) "Add" else "Save")
      }
      TextButton(onClick = { reset() }) { Text("New variable") }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventActionsInspector(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var expanded by remember(node.id) { mutableStateOf(node.eventBindings.isNotEmpty()) }
  var variable by
    remember(node.id) { mutableStateOf(document.stateVariables.keys.firstOrNull().orEmpty()) }
  var kind by remember(node.id) { mutableStateOf("set") }
  var value by remember(node.id) { mutableStateOf("") }
  var editingIndex by remember(node.id) { mutableStateOf<Int?>(null) }
  val selectedDeclaration = document.stateVariables[variable] as? JsonObject
  val initialValue = selectedDeclaration?.get("initialValue") as? JsonPrimitive
  val isFlag =
    (selectedDeclaration?.get("valueType") as? JsonPrimitive)?.content == "bool" ||
      (initialValue?.isString == false && initialValue.booleanOrNull != null)
  val isNullable =
    (selectedDeclaration?.get("nullable") as? JsonPrimitive)?.booleanOrNull
      ?: (initialValue is JsonNull)
  val actionKinds =
    listOf("set" to "Set") +
      (if (isFlag) listOf("toggle" to "Toggle") else emptyList()) +
      (if (isNullable) listOf("selectOrClear" to "Select / clear") else emptyList())
  LaunchedEffect(variable, selectedDeclaration) {
    if (kind !in actionKinds.map { it.first }) kind = "set"
  }
  val events = (listOf("click") + node.eventBindings.keys).distinct()
  var event by remember(node.id) { mutableStateOf("click") }
  TextButton(onClick = { expanded = !expanded }) { Text("Actions") }
  if (!expanded) return
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      "Run in order when someone interacts with this component.",
      style = MaterialTheme.typography.bodySmall,
    )
    FlowRow {
      events.forEach { candidate ->
        FilterChip(
          event == candidate,
          {
            event = candidate
            editingIndex = null
          },
          label = { Text(if (candidate == "click") "On click" else candidate) },
        )
      }
    }
    val actions = (node.eventBindings[event] as? JsonArray).orEmpty()
    fun write(updated: List<JsonElement>) {
      dispatch(UiBuilderEditorEvent.SetEventBinding(node.id, event, JsonArray(updated)))
    }
    actions.forEachIndexed { index, element ->
      val action = element as? JsonObject
      val actionKind = (action?.get("type") as? JsonPrimitive)?.content.orEmpty()
      val actionVariable = (action?.get("variable") as? JsonPrimitive)?.content.orEmpty()
      Text(
        "${index + 1}. $actionKind $actionVariable ${(action?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty()}",
        style = MaterialTheme.typography.bodySmall,
      )
      Row {
        if (actionKind in setOf("set", "select", "setText", "toggle", "selectOrClear"))
          TextButton(
            onClick = {
              editingIndex = index
              kind = if (actionKind in setOf("select", "setText")) "set" else actionKind
              variable = actionVariable
              value = (action?.get("value") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
          ) {
            Text("Edit")
          }
        TextButton(
          enabled = index > 0,
          onClick = {
            val next = actions.toMutableList()
            next[index] = next[index - 1]
            next[index - 1] = element
            write(next)
            editingIndex = null
          },
          modifier = Modifier.semantics { contentDescription = "Move action ${index + 1} up" },
        ) {
          Text("Up")
        }
        TextButton(
          onClick = {
            write(actions.filterIndexed { i, _ -> i != index })
            editingIndex = null
          },
          modifier = Modifier.semantics { contentDescription = "Remove action ${index + 1}" },
        ) {
          Text("Remove")
        }
      }
    }
    if (document.stateVariables.isEmpty()) {
      Text(
        "Add a state value in Screen to give this action something to change.",
        style = MaterialTheme.typography.bodySmall,
      )
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Screen)) }
      ) {
        Text("Open Screen")
      }
    } else {
      Text(
        if (editingIndex == null) "Add action" else "Edit action ${editingIndex!! + 1}",
        style = MaterialTheme.typography.labelLarge,
      )
      var showVariables by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(onClick = { showVariables = true }) {
          Text(variable.ifBlank { "Choose state" })
        }
        DropdownMenu(showVariables, { showVariables = false }) {
          document.stateVariables.keys.forEach { name ->
            DropdownMenuItem(
              text = { Text(name) },
              onClick = {
                variable = name
                showVariables = false
              },
              modifier = Modifier.semantics { contentDescription = "Use state $name" },
            )
          }
        }
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actionKinds.forEach { (id, label) ->
          FilterChip(kind == id, { kind = id }, label = { Text(label) })
        }
      }
      if (kind != "toggle")
        OutlinedTextField(
          value,
          { value = it },
          Modifier.fillMaxWidth().onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
          label = { Text("Value") },
          singleLine = true,
        )
      TextButton(
        enabled = variable in document.stateVariables,
        onClick = {
          val action =
            when (kind) {
              "toggle" -> EditorStateAction.Toggle(variable)
              "selectOrClear" -> EditorStateAction.SelectOrClear(variable, value)
              else -> EditorStateAction.Set(variable, value)
            }
          dispatch(UiBuilderEditorEvent.AppendAction(node.id, event, action, editingIndex))
        },
        modifier = Modifier.semantics { contentDescription = "Save event action" },
      ) {
        Text(if (editingIndex == null) "Add action" else "Save action")
      }
      if (editingIndex != null) TextButton(onClick = { editingIndex = null }) { Text("New action") }
    }
  }
}

/** Edits one atomic property, using the same mutation as MCP. Drafts never change the canvas. */
@Composable
internal fun StateSelectionInspector(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val saved = node.stateSelection()
  var expanded by remember(node.id) { mutableStateOf(saved != null) }
  var variable by
    remember(node.id, saved) {
      mutableStateOf((saved?.selector?.get("variable") as? JsonPrimitive)?.contentOrNull.orEmpty())
    }
  val children = node.slots["children"].orEmpty()
  var values by
    remember(node.id, saved, children) {
      mutableStateOf(children.associateWith { saved?.cases?.get(it)?.content.orEmpty() })
    }
  var fallback by remember(node.id, saved) { mutableStateOf(saved?.fallback) }
  val declaration = document.stateVariables[variable] as? JsonObject
  val kind =
    StateEditorKind.entries.firstOrNull {
      it.wire == (declaration?.get("valueType") as? JsonPrimitive)?.content
    }
      ?: when (val initial = declaration?.get("initialValue") as? JsonPrimitive) {
        null,
        JsonNull -> null
        else ->
          when {
            initial.isString -> StateEditorKind.Text
            initial.booleanOrNull != null -> StateEditorKind.Flag
            initial.intOrNull != null -> StateEditorKind.Number
            else -> StateEditorKind.Decimal
          }
      }
  TextButton(onClick = { expanded = !expanded }) { Text("Show by state") }
  if (!expanded) return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      "Show one child for the current value. Use a Box or Column for a case with several layers.",
      style = MaterialTheme.typography.bodySmall,
    )
    var choosing by remember { mutableStateOf(false) }
    Box {
      OutlinedButton(onClick = { choosing = true }) { Text(variable.ifEmpty { "Choose state" }) }
      DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
        document.stateVariables.keys.forEach { name ->
          DropdownMenuItem(
            text = { Text(name) },
            onClick = {
              variable = name
              choosing = false
            },
          )
        }
      }
    }
    if (document.stateVariables.isEmpty())
      Text("Add a value in Screen → State first.", style = MaterialTheme.typography.bodySmall)
    children.forEach { id ->
      val label =
        (document.nodes[id]?.properties?.get("text") as? JsonObject)?.get("value")?.let {
          (it as? JsonPrimitive)?.contentOrNull
        } ?: id
      Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
          value = values[id].orEmpty(),
          onValueChange = { values = values + (id to it) },
          label = { Text("$label · value") },
          enabled = fallback != id,
          singleLine = true,
          modifier =
            Modifier.weight(1f)
              .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
              .semantics { contentDescription = "Case value $id" },
        )
        Checkbox(
          checked = fallback == id,
          onCheckedChange = { fallback = if (it) id else null },
          modifier = Modifier.semantics { contentDescription = "Fallback $id" },
        )
      }
    }
    Text(
      "Checked child is the fallback for other values. Without one, no child is shown.",
      style = MaterialTheme.typography.bodySmall,
    )
    val parsed =
      children.filter { it != fallback }.associateWith { kind?.parse(values[it].orEmpty()) }
    val selection =
      if (parsed.values.all { it != null })
        StateSelection(
          buildJsonObject {
            put("type", "state")
            put("variable", variable)
          },
          parsed.mapValues { requireNotNull(it.value) },
          fallback,
        )
      else null
    val issue =
      if (selection == null)
        "Enter a ${kind?.label?.lowercase() ?: "matching"} value for each case."
      else
        stateSelectionIssue(
          node.copy(
            properties = JsonObject(node.properties + (SHOW_BY_STATE to selection.encode()))
          ),
          document.stateVariables,
        )
    if (issue != null)
      Text(
        issue,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    Row {
      Button(
        enabled = issue == null && selection != null,
        onClick = { dispatch(UiBuilderEditorEvent.SetStateSelection(node.id, selection)) },
      ) {
        Text("Apply cases")
      }
      if (SHOW_BY_STATE in node.properties)
        TextButton(onClick = { dispatch(UiBuilderEditorEvent.SetStateSelection(node.id, null)) }) {
          Text("Show all children")
        }
    }
  }
}
