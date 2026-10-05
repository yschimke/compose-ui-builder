package ee.schimke.composeai.uibuilder.export.production

data class ProductionInput(val path: String, val file: ProductionUidFile)

data class ProductionContractIssue(
  val code: String,
  val file: String,
  val declaration: String? = null,
  val nodeId: String? = null,
  val field: String? = null,
  val message: String,
)

/** A validated declaration graph, not a claim that its design can already be emitted as Compose. */
class ValidatedProductionContract internal constructor(val inputs: List<ProductionInput>) {
  val models: Map<String, ProductionModel> = inputs.flatMap { it.file.models }.associateBy { it.id }
  val entryPoints: Map<String, ProductionEntryPoint> =
    inputs.mapNotNull { it.file.entryPoint }.associateBy { it.id }
}

sealed interface ProductionContractResult {
  data class Valid(val contract: ValidatedProductionContract) : ProductionContractResult

  data class Invalid(val issues: List<ProductionContractIssue>) : ProductionContractResult
}

/** Validates application API declarations without loading project Kotlin or linking Compose. */
object ProductionContractValidator {
  fun validate(inputs: List<ProductionInput>): ProductionContractResult {
    val pass = Validation(inputs.sortedBy { it.path })
    pass.validate()
    return if (pass.issues.isEmpty()) {
      ProductionContractResult.Valid(ValidatedProductionContract(pass.inputs))
    } else {
      ProductionContractResult.Invalid(pass.issues.distinct())
    }
  }

  private class Validation(val inputs: List<ProductionInput>) {
    val issues = mutableListOf<ProductionContractIssue>()
    private val models = mutableMapOf<String, Pair<ProductionInput, ProductionModel>>()
    private val entries = mutableMapOf<String, Pair<ProductionInput, ProductionEntryPoint>>()
    private val symbols = mutableMapOf<String, ProductionInput>()
    private val generatedPaths = mutableMapOf<String, String>()

    private fun issue(
      code: String,
      input: ProductionInput,
      declaration: String? = null,
      nodeId: String? = null,
      field: String? = null,
      message: String,
    ) {
      issues += ProductionContractIssue(code, input.path, declaration, nodeId, field, message)
    }

    fun validate() {
      if (inputs.isEmpty()) {
        issues +=
          ProductionContractIssue(
            "NO_INPUTS",
            "",
            message = "at least one production input is required",
          )
      }
      val paths = mutableSetOf<String>()
      inputs.forEach { input ->
        if (!isProductionUidPath(input.path)) {
          issue("INVALID_FILE_PATH", input, message = "expected a project-relative .uid path")
        }
        if (!paths.add(input.path)) {
          issue("DUPLICATE_FILE", input, message = "input path is registered more than once")
        }
        if (input.file.schema !in ProductionUidFiles.SCHEMAS) {
          issue("UNSUPPORTED_SCHEMA", input, message = "unsupported schema '${input.file.schema}'")
        }
        input.file.models.forEach { model ->
          if (model.id.isBlank()) {
            issue("INVALID_ID", input, field = "models.id", message = "model id must not be blank")
          }
          val previous = models[model.id]
          if (previous == null) models[model.id] = input to model
          if (previous != null) {
            issue(
              "DUPLICATE_MODEL",
              input,
              model.id,
              message = "model is already owned by ${previous.first.path}",
            )
          }
          symbol(
            input,
            model.id,
            model.kotlinType,
            generated = model.ownership == ModelOwnership.GENERATED,
          )
        }
        input.file.entryPoint?.let { entry ->
          if (entry.id.isBlank()) {
            issue(
              "INVALID_ID",
              input,
              field = "entryPoint.id",
              message = "entry point id must not be blank",
            )
          }
          val previous = entries[entry.id]
          if (previous == null) entries[entry.id] = input to entry
          if (previous != null) {
            issue(
              "DUPLICATE_ENTRY_POINT",
              input,
              entry.id,
              message = "entry point is already owned by ${previous.first.path}",
            )
          }
          symbol(input, entry.id, entry.kotlinFunction, generated = true)
        }
      }
      inputs.forEach { input ->
        input.file.imports.forEach { path ->
          if (!isProductionUidPath(path)) {
            issue(
              "INVALID_IMPORT",
              input,
              field = "imports",
              message = "'$path' is not a project-relative .uid path",
            )
          } else if (path !in paths) {
            issue(
              "MISSING_IMPORT",
              input,
              field = "imports",
              message = "'$path' is not among the resolved project inputs",
            )
          }
        }
        input.file.models.forEach { validateModel(input, it) }
        val entry = input.file.entryPoint
        if (entry == null && input.file.design != null) {
          issue(
            "UNDECLARED_ENTRY_POINT",
            input,
            message = "a production design requires a declared entry point",
          )
        }
        if (entry == null && input.file.models.isEmpty()) {
          issue(
            "EMPTY_DECLARATION",
            input,
            message = "a production file must declare models or an entry point",
          )
        }
        if (entry != null) validateEntry(input, entry)
      }
      cycles(
        models.mapValues { (_, value) -> value.second.fields.flatMap { modelReferences(it.type) } },
        "MODEL_CYCLE",
      ) { id ->
        models.getValue(id).first
      }
      cycles(
        entries.mapValues { (_, value) -> value.second.components.map { it.componentId } },
        "COMPONENT_CYCLE",
      ) { id ->
        entries.getValue(id).first
      }
    }

    private fun symbol(input: ProductionInput, id: String, name: String, generated: Boolean) {
      if (!isProductionQualifiedName(name)) {
        issue(
          "INVALID_KOTLIN_NAME",
          input,
          id,
          field = "kotlinName",
          message = "'$name' must be a qualified Kotlin name without escaping",
        )
      }
      if (generated && name.substringBefore('.') in setOf("kotlin", "java", "javax", "androidx")) {
        issue(
          "RESERVED_PACKAGE",
          input,
          id,
          message = "generated declarations cannot own platform package '$name'",
        )
      }
      if (generated) {
        val path = name.lowercase()
        val previousName = generatedPaths[path]
        if (previousName == null) generatedPaths[path] = name
        else if (previousName != name) {
          issue(
            "OUTPUT_PATH_COLLISION",
            input,
            id,
            message =
              "'$name' and '$previousName' would overwrite the same file on a case-insensitive filesystem",
          )
        }
      }
      val previous = symbols[name]
      if (previous == null) symbols[name] = input
      if (previous != null) {
        issue(
          "DUPLICATE_KOTLIN_SYMBOL",
          input,
          id,
          message = "'$name' is already declared in ${previous.path}",
        )
      }
    }

    private fun validateModel(input: ProductionInput, model: ProductionModel) {
      if (model.ownership == ModelOwnership.GENERATED && model.fields.isEmpty()) {
        issue(
          "EMPTY_DATA_CLASS",
          input,
          model.id,
          message = "a generated data class needs at least one field",
        )
      }
      val names = mutableSetOf<String>()
      model.fields.forEach { field ->
        if (!isProductionIdentifier(field.name)) {
          issue(
            "INVALID_FIELD_NAME",
            input,
            model.id,
            field = field.name,
            message = "field must be a Kotlin identifier without escaping",
          )
        }
        if (!names.add(field.name)) {
          issue(
            "DUPLICATE_FIELD",
            input,
            model.id,
            field = field.name,
            message = "field is declared more than once",
          )
        }
        if (model.ownership == ModelOwnership.GENERATED && field.property != null) {
          issue(
            "GENERATED_FIELD_MAPPING",
            input,
            model.id,
            field = field.name,
            message = "only external models can map a Kotlin property",
          )
        }
        if (field.property != null && !isProductionIdentifier(field.property)) {
          issue(
            "INVALID_PROPERTY_MAPPING",
            input,
            model.id,
            field = field.name,
            message = "external property must be a single Kotlin identifier",
          )
        }
        validateType(input, model.id, field.name, field.type)
      }
    }

    private fun validateType(
      input: ProductionInput,
      id: String,
      field: String,
      type: ProductionType,
    ) {
      modelReferences(type).forEach { referenced ->
        if (referenced !in models) {
          issue(
            "UNKNOWN_MODEL",
            input,
            id,
            field = field,
            message = "model '$referenced' is not declared",
          )
        } else if (!canSee(input, models.getValue(referenced).first.path)) {
          issue(
            "MODEL_NOT_IMPORTED",
            input,
            id,
            field = field,
            message = "model '$referenced' requires an import of its owning file",
          )
        }
      }
    }

    private fun canSee(input: ProductionInput, path: String): Boolean {
      val byPath = inputs.associateBy { it.path }
      val visited = mutableSetOf<String>()
      fun visit(current: String): Boolean {
        if (current == path) return true
        if (!visited.add(current)) return false
        return byPath[current]?.file?.imports.orEmpty().any(::visit)
      }
      return visit(input.path)
    }

    private fun validateEntry(input: ProductionInput, entry: ProductionEntryPoint) {
      validateType(input, entry.id, "inputModel", ProductionType.Model(entry.inputModel))
      val names = mutableSetOf("data", "modifier")
      entry.events.forEach { event ->
        if (!isProductionIdentifier(event.name) || !names.add(event.name)) {
          issue(
            "INVALID_EVENT_NAME",
            input,
            entry.id,
            field = event.name,
            message = "event must have a unique Kotlin name other than data or modifier",
          )
        }
        event.payload?.let { validateType(input, entry.id, event.name, it) }
      }
      val design = input.file.design
      if (design == null) {
        issue(
          "MISSING_DESIGN",
          input,
          entry.id,
          message = "a composable entry point requires a design",
        )
        return
      }
      if (
        design.schema !in
          setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")
      ) {
        issue(
          "UNSUPPORTED_DESIGN_SCHEMA",
          input,
          entry.id,
          message = "unsupported embedded design schema '${design.schema}'",
        )
      }
      if (design.roots != listOf(entry.root) || entry.root !in design.nodes) {
        issue(
          "INVALID_ROOT",
          input,
          entry.id,
          nodeId = entry.root,
          message = "entry root must be the design's single existing root",
        )
      }
      if (design.stateVariables.isNotEmpty()) {
        issue(
          "OWNED_APPLICATION_STATE",
          input,
          entry.id,
          field = "stateVariables",
          message = "production state must be supplied through the input model",
        )
      }
      design.nodes.forEach { (id, node) ->
        if (node.eventBindings.isNotEmpty()) {
          issue(
            "PREVIEW_STATE_ACTION",
            input,
            entry.id,
            nodeId = id,
            field = "eventBindings",
            message = "preview actions cannot define production events",
          )
        }
      }
      val bound = mutableSetOf<Pair<String, String>>()
      entry.bindings.forEach { binding ->
        node(input, entry, binding.nodeId)
        if (!isProductionIdentifier(binding.property)) {
          issue(
            "INVALID_BINDING_PROPERTY",
            input,
            entry.id,
            binding.nodeId,
            binding.property,
            "binding property must be a Kotlin identifier",
          )
        }
        if (!bound.add(binding.nodeId to binding.property)) {
          issue(
            "DUPLICATE_BINDING",
            input,
            entry.id,
            binding.nodeId,
            binding.property,
            "node property has more than one binding",
          )
        }
        validateType(input, entry.id, binding.property, binding.expectedType)
        val actual = readPath(input, entry, binding.nodeId, binding.property, binding.path)
        if (actual != null && actual != binding.expectedType) {
          issue(
            "BINDING_TYPE_MISMATCH",
            input,
            entry.id,
            binding.nodeId,
            binding.property,
            "binding reads $actual but declares ${binding.expectedType}",
          )
        }
      }
      entry.eventBindings.forEach { binding ->
        node(input, entry, binding.nodeId)
        fun invalid(code: String, message: String) =
          issue(code, input, entry.id, binding.nodeId, binding.property, message)
        if (!isProductionIdentifier(binding.property)) {
          invalid("INVALID_EVENT_PROPERTY", "event property must be a Kotlin identifier")
        }
        if (!bound.add(binding.nodeId to binding.property)) {
          invalid("DUPLICATE_BINDING", "node property has more than one binding")
        }
        if (entry.components.any { it.nodeId == binding.nodeId }) {
          invalid("COMPONENT_EVENT_BINDING", "forward component events through the component use")
        }
        if (design.nodes[binding.nodeId]?.properties?.containsKey(binding.property) == true) {
          invalid("CONFLICTING_EVENT_PROPERTY", "event callback also has an authored property")
        }
        val event = entry.events.singleOrNull { it.name == binding.event }
        if (event == null) {
          invalid("UNKNOWN_EVENT", "event '${binding.event}' is not declared")
        } else if ((event.payload == null) != (binding.payloadPath == null)) {
          invalid(
            "EVENT_PAYLOAD_REQUIRED",
            "payload path must be supplied exactly when the event declares a payload",
          )
        } else if (binding.payloadPath != null) {
          val actual = readPath(input, entry, binding.nodeId, binding.property, binding.payloadPath)
          if (actual != null && actual != event.payload) {
            invalid("EVENT_PAYLOAD_MISMATCH", "event requires ${event.payload}, got $actual")
          }
        }
      }
      val placements = mutableSetOf<String>()
      entry.components.forEach { use ->
        node(input, entry, use.nodeId)
        if (!placements.add(use.nodeId)) {
          issue(
            "DUPLICATE_COMPONENT_USE",
            input,
            entry.id,
            use.nodeId,
            message = "node places more than one production component",
          )
        }
        val target = entries[use.componentId]
        if (target == null || target.second.kind != EntryPointKind.COMPONENT) {
          issue(
            "UNKNOWN_COMPONENT",
            input,
            entry.id,
            use.nodeId,
            message = "'${use.componentId}' must name a declared component",
          )
        } else {
          if (!canSee(input, target.first.path)) {
            issue(
              "COMPONENT_NOT_IMPORTED",
              input,
              entry.id,
              use.nodeId,
              message = "component requires an import of ${target.first.path}",
            )
          }
          val actual = readPath(input, entry, use.nodeId, "data", use.dataPath)
          if (actual != null && actual != ProductionType.Model(target.second.inputModel)) {
            issue(
              "COMPONENT_INPUT_MISMATCH",
              input,
              entry.id,
              use.nodeId,
              "data",
              "component requires model '${target.second.inputModel}', got $actual",
            )
          }
          val childEvents = target.second.events.associateBy { it.name }
          val parentEvents = entry.events.associateBy { it.name }
          childEvents.forEach { (name, child) ->
            val parent = parentEvents[use.events[name]]
            if (parent == null || parent.payload != child.payload) {
              issue(
                "COMPONENT_EVENT_MISMATCH",
                input,
                entry.id,
                use.nodeId,
                name,
                "component event needs an explicitly forwarded callback with the same payload type",
              )
            }
          }
          (use.events.keys - childEvents.keys).forEach { name ->
            issue(
              "UNKNOWN_COMPONENT_EVENT",
              input,
              entry.id,
              use.nodeId,
              name,
              "component does not declare this event",
            )
          }
        }
      }
    }

    private fun node(input: ProductionInput, entry: ProductionEntryPoint, nodeId: String) {
      if (nodeId !in input.file.design!!.nodes) {
        issue(
          "UNKNOWN_NODE",
          input,
          entry.id,
          nodeId,
          message = "binding refers to a node absent from the design",
        )
      }
    }

    private fun readPath(
      input: ProductionInput,
      entry: ProductionEntryPoint,
      nodeId: String,
      field: String,
      path: List<String>,
    ): ProductionType? {
      var type: ProductionType = ProductionType.Model(entry.inputModel)
      path.forEach { segment ->
        if (type.nullable) {
          issue(
            "NULLABLE_PATH",
            input,
            entry.id,
            nodeId,
            field,
            "path crosses a nullable value before '$segment'; explicit null handling is required",
          )
          return null
        }
        val model = (type as? ProductionType.Model)?.let { models[it.modelId]?.second }
        if (model == null) {
          issue(
            "INVALID_DATA_PATH",
            input,
            entry.id,
            nodeId,
            field,
            "path segment '$segment' requires a declared model",
          )
          return null
        }
        val property = model.fields.singleOrNull { it.name == segment }
        if (property == null) {
          issue(
            "UNKNOWN_DATA_FIELD",
            input,
            entry.id,
            nodeId,
            field,
            "model '${model.id}' has no unique field '$segment'",
          )
          return null
        }
        type = property.type
      }
      return type
    }

    private fun cycles(
      graph: Map<String, List<String>>,
      code: String,
      owner: (String) -> ProductionInput,
    ) {
      val visiting = mutableSetOf<String>()
      val visited = mutableSetOf<String>()
      fun visit(id: String) {
        if (id !in graph || id in visited) return
        if (!visiting.add(id)) {
          issue(
            code,
            owner(id),
            id,
            message = "recursive declaration involving '$id' is not supported",
          )
          return
        }
        graph.getValue(id).forEach(::visit)
        visiting.remove(id)
        visited.add(id)
      }
      graph.keys.sorted().forEach(::visit)
    }
  }
}

internal fun modelReferences(type: ProductionType): List<String> =
  when (type) {
    is ProductionType.Scalar -> emptyList()
    is ProductionType.Model -> listOf(type.modelId)
    is ProductionType.ListType -> modelReferences(type.element)
  }

internal fun isProductionUidPath(path: String): Boolean =
  path.endsWith(".uid") &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
    path.none { it == '\\' || it == ':' || it.isISOControl() }

internal fun isProductionQualifiedName(name: String): Boolean =
  name.contains('.') && name.split('.').all(::isProductionIdentifier)

internal fun isProductionIdentifier(name: String): Boolean =
  name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) &&
    name.any { it != '_' } &&
    name !in KOTLIN_KEYWORDS

private val KOTLIN_KEYWORDS =
  setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
  )
