package ee.schimke.composeai.uibuilder.codegen

import ee.schimke.composeai.discovery.*
import ee.schimke.composeai.uibuilder.export.*
import ee.schimke.composeai.uibuilder.export.production.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Opt-in build generator. The existing single-file exporter is unchanged. */
@OptIn(
  CompilerConfiguration.Internals::class,
  org.jetbrains.kotlin.CoreEnvironmentDeprecation::class,
  org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
)
object ProductionComposeGenerator {
  private const val MODIFIER = "androidx.compose.ui.Modifier"
  private const val BOX = "layout/box"

  private data class Read(
    val path: List<String>,
    val type: ProductionType.Scalar,
    val fallback: JsonPrimitive? = null,
  )

  private data class DynamicPlacement(
    val use: ProductionComponentUse,
    val entry: ProductionEntryPoint,
    val symbol: String,
  )

  private data class Callback(val event: String, val payloadPath: List<String>?)

  private data class Body(
    val entry: ProductionEntryPoint,
    val reads: List<Read>,
    val callbacks: List<Callback>,
    val placements: List<DynamicPlacement>,
    val root: ScreenNode,
    val record: ComponentRecordFile,
  )

  fun generate(
    contract: ValidatedProductionContract,
    record: ComponentRecordFile,
    catalogDigest: String,
  ): List<ProductionGeneratedFile> {
    val bodies = linkedMapOf<String, Body>()
    fun body(id: String): Body {
      bodies[id]?.let {
        return it
      }
      val input = contract.inputs.single { it.file.entryPoint?.id == id }
      val entry = requireNotNull(input.file.entryPoint)
      fun check(value: Boolean, message: String) = require(value) { "${input.path}: $message" }
      check(
        input.file.catalogDigest == catalogDigest,
        "component records differ from the declared catalogDigest",
      )
      val design = requireNotNull(input.file.design)
      check(
        design.components.isEmpty(),
        "declare reusable components in imported production .uid files",
      )
      check(design.assets.isEmpty(), "assets need a project-owned asset adapter")
      val reachable = mutableSetOf<String>()
      fun visit(node: String) {
        check(reachable.add(node), "node $node is reused or recursive; use a declared component")
        design.nodes.getValue(node).slots.values.flatten().forEach(::visit)
      }
      visit(entry.root)
      check(reachable == design.nodes.keys, "all design nodes must belong to the declared root")
      // Declared reads are rewritten to generated `pN` names, which the projection merges by
      // name; any other design-level binding would silently alias one of them.
      fun bindings(element: JsonElement): List<String> =
        when (element) {
          is JsonObject ->
            listOfNotNull(
              (element["value"] as? JsonPrimitive)
                ?.takeIf { (element["type"] as? JsonPrimitive)?.contentOrNull == "binding" }
                ?.content
            ) + element.values.flatMap(::bindings)
          is JsonArray -> element.flatMap(::bindings)
          else -> emptyList()
        }
      val stray =
        design.nodes.flatMap { (nodeId, node) ->
          val declared = entry.bindings.filter { it.nodeId == nodeId }.map { it.property }.toSet()
          node.properties
            .filterKeys { it !in declared }
            .flatMap { (property, value) -> bindings(value).map { "$nodeId.$property" to it } } +
            bindings(node.modifiers).map { "$nodeId modifiers" to it }
        }
      stray.firstOrNull()?.let { (where, name) ->
        check(false, "$where: design binding `$name` is not a declared entry-point binding")
      }
      val reads = mutableListOf<Read>()
      val nodes = design.nodes.toMutableMap()
      entry.bindings.forEach { binding ->
        val type = binding.expectedType as? ProductionType.Scalar
        check(
          type != null && !type.nullable,
          "${binding.nodeId}.${binding.property}: only non-null scalar reads are supported",
        )
        val parameter = "p${reads.size}"
        reads += Read(binding.path, requireNotNull(type), binding.fallback)
        val node = nodes.getValue(binding.nodeId)
        nodes[binding.nodeId] =
          node.copy(
            properties =
              JsonObject(
                node.properties +
                  (binding.property to
                    buildJsonObject {
                      put("type", "binding")
                      put("value", parameter)
                    })
              )
          )
      }
      val callbacks = mutableListOf<Callback>()
      val callbackArguments = linkedMapOf<String, MutableMap<String, ScreenValue>>()
      entry.eventBindings.forEach { binding ->
        val parameter = "c${callbacks.size}"
        callbacks += Callback(binding.event, binding.payloadPath)
        callbackArguments.getOrPut(binding.nodeId) { linkedMapOf() }[binding.property] =
          ScreenValue.ParameterRead(parameter, "kotlin.Function0")
      }
      val overrides = linkedMapOf<String, ScreenNode>()
      val placements = mutableListOf<DynamicPlacement>()
      val children =
        entry.components.map { use ->
          val child = body(use.componentId)
          val placement = nodes.getValue(use.nodeId)
          check(
            placement.properties.isEmpty() &&
              placement.modifiers.isEmpty() &&
              placement.slots.isEmpty() &&
              placement.component == null,
            "${use.nodeId}: component placement must contain only its declared data mapping",
          )
          check(
            entry.bindings.none { it.nodeId == use.nodeId },
            "${use.nodeId}: bind component data through dataPath",
          )
          if (use.keyPath != null || use.onNull != null || child.placements.isNotEmpty()) {
            val symbol = helperFqn(entry) + "Placement" + placements.size
            placements += DynamicPlacement(use, child.entry, symbol)
            overrides[use.nodeId] = ScreenNode(componentId = symbol)
            return@map child
          }
          val arguments =
            linkedMapOf<String, ScreenValue>(
              "modifier" to ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER)
            )
          child.reads.forEachIndexed { index, read ->
            val parameter = "p${reads.size}"
            reads += read.copy(path = use.dataPath + read.path)
            arguments["p$index"] = ScreenValue.ParameterRead(parameter, read.type.scalar.kotlinType)
          }
          child.callbacks.forEachIndexed { index, callback ->
            val parameter = "c${callbacks.size}"
            callbacks +=
              Callback(
                use.events.getValue(callback.event),
                callback.payloadPath?.let { use.dataPath + it },
              )
            arguments["c$index"] = ScreenValue.ParameterRead(parameter, "kotlin.Function0")
          }
          overrides[use.nodeId] =
            ScreenNode(componentId = helperFqn(child.entry), arguments = arguments)
          child
        }
      val typed = design.copy(nodes = nodes).toDesignDocumentV1()
      val projected =
        ScreenDocumentProjection.projectProduction(
          typed,
          nodeOverrides = overrides,
          callbackArguments = callbackArguments,
        )
      check(
        projected is ScreenDocumentProjection.Outcome.Projected,
        (projected as? ScreenDocumentProjection.Outcome.Refused)?.reasons?.joinToString("; ")
          ?: "projection failed",
      )
      projected as ScreenDocumentProjection.Outcome.Projected
      check(projected.assetPlaceholders.isEmpty(), "asset placeholders are not production output")
      // A variable font text calls a declaration flexpress generates at export, which this lane
      // does not write; refused by name rather than emitted as a call to nothing.
      check(projected.variableFontTexts.isEmpty()) {
        "variable font text is not production output yet: " +
          projected.variableFontTexts.joinToString { it.functionName }
      }
      check(
        projected.document.state.isEmpty() && projected.document.functions.isEmpty(),
        "stateful or implicit supporting functions are not supported",
      )
      val root =
        ScreenNode(
          BOX,
          arguments = mapOf("modifier" to ScreenValue.ParameterRead("modifier", MODIFIER)),
          slots = mapOf("content" to listOf(projected.document.root)),
        )
      val combined =
        projected
          .resolvable(record.callableAliases())
          .copy(
            components =
              projected.resolvable(record.callableAliases()).components +
                placements.map { placement ->
                  ComponentRecord(
                    canonicalId = placement.symbol,
                    symbol =
                      ComponentSymbol(
                        jvmOwner = placement.symbol + "Kt",
                        callable = placement.symbol,
                        name = placement.symbol.substringAfterLast('.'),
                        origin = ComponentOrigin.PROJECT,
                      ),
                    signatureKnown = true,
                    parameters = emptyList(),
                    code =
                      ComponentCode(
                        call = placement.symbol.substringAfterLast('.') + "()",
                        imports = listOf(placement.symbol),
                      ),
                  )
                } +
                children
                  .map { child ->
                    ComponentRecord(
                      canonicalId = helperFqn(child.entry),
                      symbol =
                        ComponentSymbol(
                          jvmOwner =
                            child.entry.kotlinFunction.substringBeforeLast('.') +
                              "." +
                              child.entry.kotlinFunction.substringAfterLast('.') +
                              "Kt",
                          callable = helperFqn(child.entry),
                          name = helperName(child.entry),
                          origin = ComponentOrigin.PROJECT,
                        ),
                      signatureKnown = true,
                      parameters =
                        listOf(TargetParameter("modifier", "Modifier", typeFqn = MODIFIER)) +
                          child.reads.mapIndexed { index, read ->
                            TargetParameter(
                              "p$index",
                              read.type.scalar.kotlinType.substringAfterLast('.'),
                              typeFqn = read.type.scalar.kotlinType,
                            )
                          } +
                          child.callbacks.indices.map { index ->
                            TargetParameter(
                              "c$index",
                              "() -> Unit",
                              typeFqn = "kotlin.Function0",
                              lambdaReturnTypeFqn = "kotlin.Unit",
                            )
                          },
                      code =
                        ComponentCode(
                          call = helperName(child.entry) + "()",
                          imports = listOf(helperFqn(child.entry)),
                        ),
                    )
                  }
                  .distinctBy { it.canonicalId }
          )
      return Body(entry, reads, callbacks, placements, root, combined).also { bodies[id] = it }
    }
    contract.entryPoints.keys.sorted().forEach(::body)
    val disposable = Disposer.newDisposable()
    try {
      val environment =
        KotlinCoreEnvironment.createForProduction(
          disposable,
          CompilerConfiguration().apply {
            extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
          },
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
      val psi = KtPsiFactory(environment.project)
      return (ProductionModelGenerator.generate(contract) +
          bodies.values.map { body ->
            val entry = body.entry
            val name = helperName(entry)
            val parameters =
              listOf(ScreenParameter.Value("modifier", MODIFIER)) +
                body.reads.mapIndexed { index, read ->
                  ScreenParameter.Value("p$index", read.type.scalar.kotlinType)
                } +
                body.callbacks.indices.map { ScreenParameter.Callback("c$it") }
            val screen =
              ScreenDocument(
                "UidValidationHost",
                // The host is discarded by PSI extraction; it never invents callback
                // implementations.
                ScreenNode(BOX),
                functions = listOf(ScreenFunction(name, parameters, body.root)),
              )
            val generated =
              ScreenGenerator.generate(
                screen,
                body.record,
                entry.kotlinFunction.substringBeforeLast('.'),
                ScreenExportGate.EXPRESSION_PACKAGES,
              )
            require(generated is ScreenGenerator.Result.Emitted) {
              "${entry.id}: ${(generated as ScreenGenerator.Result.Refused).reasons.joinToString("; ")}"
            }
            val file = psi.createFile(generated.source)
            require(file.declarations.size == 2) {
              "${entry.id}: unexpected generated declarations; refusing incomplete extraction"
            }
            val function =
              file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == name }
            val references =
              PsiTreeUtil.findChildrenOfType(
                  function.bodyExpression,
                  KtNameReferenceExpression::class.java,
                )
                .map { it.getReferencedName() }
                .toSet()
            body.reads.indices.forEach { index ->
              require("p$index" in references) {
                "${entry.id}: binding p$index was not projected; refusing to drop a data binding"
              }
            }
            body.callbacks.indices.forEach { index ->
              require("c$index" in references) {
                "${entry.id}: callback c$index was not projected; refusing to drop an event binding"
              }
            }
            val calls =
              PsiTreeUtil.findChildrenOfType(function, KtCallExpression::class.java).mapNotNull {
                it.calleeExpression?.text
              }
            require(
              calls.none {
                it.substringAfterLast('.').startsWith("remember") ||
                  it.substringAfterLast('.') in
                    setOf(
                      "mutableStateOf",
                      "mutableIntStateOf",
                      "mutableLongStateOf",
                      "mutableFloatStateOf",
                      "mutableStateListOf",
                      "mutableStateMapOf",
                    )
              }
            ) {
              "${entry.id}: generated body owns Compose state"
            }
            // ScreenGenerator still emits all visual bodies. Replace only our checked, synthetic
            // placement call nodes with composable slots; control flow stays in the data wrapper.
            val replacements = mutableListOf<Pair<IntRange, String>>()
            fun replacement(start: Int, end: Int, text: String) {
              replacements +=
                (start - function.textRange.startOffset until
                  end - function.textRange.startOffset) to text
            }
            body.placements.forEachIndexed { index, placement ->
              val sites =
                PsiTreeUtil.findChildrenOfType(function, KtCallExpression::class.java).filter {
                  it.calleeExpression?.text == placement.symbol.substringAfterLast('.')
                }
              require(sites.size == 1 && sites.single().valueArguments.isEmpty()) {
                "${entry.id}: placement was not projected exactly once"
              }
              val site = sites.single()
              replacement(site.textRange.startOffset, site.textRange.endOffset, "uidSlot$index()")
            }
            if (body.placements.isNotEmpty()) {
              val parameters = requireNotNull(function.valueParameterList)
              val values =
                function.valueParameters.map { it.text } +
                  body.placements.indices.map {
                    "uidSlot$it: @androidx.compose.runtime.Composable () -> kotlin.Unit"
                  }
              replacement(
                parameters.textRange.startOffset,
                parameters.textRange.endOffset,
                values.joinToString(", ", "(", ")"),
              )
            }
            val privateToken =
              requireNotNull(function.modifierList?.getModifier(KtTokens.PRIVATE_KEYWORD))
            replacement(
              privateToken.textRange.startOffset,
              privateToken.textRange.endOffset,
              "internal",
            )
            val helper =
              replacements
                .sortedByDescending { it.first.first }
                .fold(function.text) { text, (range, value) -> text.replaceRange(range, value) }
            val reservedNames = entry.events.map { it.name }.toSet()
            fun local(base: String): String =
              generateSequence(base) { it + "_" }.first { it !in reservedNames }
            val keyName = local("uidComposeKey")
            val requireName = local("uidRequire")
            val source = buildString {
              appendLine("// Generated from an opt-in project-owned .uid contract. Do not edit.")
              appendLine(file.packageDirective!!.text)
              file.importDirectives
                .filter { directive ->
                  body.placements.none { it.symbol == directive.importedFqName?.asString() }
                }
                .forEach { appendLine(it.text) }
              body.placements.forEachIndexed { index, placement ->
                appendLine(
                  "import ${placement.entry.kotlinFunction} as ${local("uidComponent$index")}"
                )
              }
              if (body.placements.any { it.use.keyPath != null }) {
                appendLine("import androidx.compose.runtime.key as $keyName")
                appendLine("import kotlin.require as $requireName")
              }
              appendLine()
              appendLine(helper)
              appendLine()
              appendLine("@androidx.compose.runtime.Composable")
              appendLine(
                "${entry.visibility.name.lowercase()} fun ${entry.kotlinFunction.substringAfterLast('.')}("
              )
              appendLine("  data: ${contract.models.getValue(entry.inputModel).kotlinType},")
              entry.events.forEach { event ->
                val payload = event.payload?.let { kotlinType(contract, it) }.orEmpty()
                appendLine("  ${event.name}: ($payload) -> kotlin.Unit,")
              }
              appendLine("  modifier: $MODIFIER = $MODIFIER,")
              appendLine(") {")
              appendLine("  $name(")
              appendLine("    modifier = modifier,")
              body.reads.forEachIndexed { index, read ->
                appendLine(
                  "    p$index = ${readExpression(contract, entry.inputModel, read.path, safe = read.fallback != null)}${read.fallback?.let { " ?: " + literal(it, read.type.scalar) }.orEmpty()},"
                )
              }
              body.callbacks.forEachIndexed { index, callback ->
                val payload =
                  callback.payloadPath
                    ?.let { readExpression(contract, entry.inputModel, it) }
                    .orEmpty()
                appendLine("    c$index = { ${callback.event}($payload) },")
              }
              body.placements.forEachIndexed { index, placement ->
                val use = placement.use
                val value = local("uidValue$index")
                val item = local("uidItem$index")
                val expression =
                  readExpression(
                    contract,
                    entry.inputModel,
                    use.dataPath,
                    safe = use.onNull != null,
                  )
                appendLine("    uidSlot$index = {")
                appendLine("      val $value = $expression")
                if (use.onNull != null) appendLine("      if ($value != null) {")
                if (use.keyPath != null) {
                  val key =
                    readExpression(
                      contract,
                      placement.entry.inputModel,
                      requireNotNull(use.keyPath),
                      receiver = item,
                    )
                  val keys = local("uidKeys$index")
                  val position = local("uidIndex$index")
                  var keyType: ProductionType = ProductionType.Model(placement.entry.inputModel)
                  requireNotNull(use.keyPath).forEach { field ->
                    keyType =
                      contract.models
                        .getValue((keyType as ProductionType.Model).modelId)
                        .fields
                        .single { it.name == field }
                        .type
                  }
                  appendLine(
                    "      val $keys: kotlin.collections.List<${kotlinType(contract, keyType)}> = $value.map { $item -> $key }"
                  )
                  appendLine(
                    "      $requireName($keys.toSet().size == $value.size) { \"Duplicate production list keys\" }"
                  )
                  appendLine("      $value.forEachIndexed { $position, $item ->")
                  appendLine("        $keyName($keys[$position]) {")
                }
                appendLine("      ${local("uidComponent$index")}(")
                appendLine("        data = ${if (use.keyPath != null) item else value},")
                placement.entry.events.forEach { event ->
                  appendLine("        ${event.name} = ${use.events.getValue(event.name)},")
                }
                appendLine("      )")
                if (use.keyPath != null) {
                  appendLine("        }")
                  appendLine("      }")
                }
                if (use.onNull != null) appendLine("      }")
                appendLine("    },")
              }
              appendLine("  )")
              appendLine("}")
            }
            ProductionGeneratedFile(entry.kotlinFunction.replace('.', '/') + ".kt", source)
          })
        .sortedBy { it.path }
    } finally {
      Disposer.dispose(disposable)
    }
  }

  private fun kotlinType(contract: ValidatedProductionContract, type: ProductionType): String =
    when (type) {
      is ProductionType.Scalar -> type.scalar.kotlinType
      is ProductionType.Model -> contract.models.getValue(type.modelId).kotlinType
      is ProductionType.ListType -> "kotlin.collections.List<${kotlinType(contract, type.element)}>"
    } + if (type.nullable) "?" else ""

  private fun readExpression(
    contract: ValidatedProductionContract,
    modelId: String,
    path: List<String>,
    safe: Boolean = false,
    receiver: String = "data",
  ): String {
    var nullable = false
    var model = contract.models.getValue(modelId)
    return receiver +
      path.joinToString("") { fieldName ->
        val field = model.fields.single { it.name == fieldName }
        (field.type as? ProductionType.Model)?.let { model = contract.models.getValue(it.modelId) }
        val access = if (safe && nullable) "?." else "."
        nullable = nullable || field.type.nullable
        "$access${field.property ?: field.name}"
      }
  }

  private fun literal(value: JsonPrimitive, type: ScalarType): String =
    when (type) {
      ScalarType.STRING ->
        buildString {
          append('"')
          value.content.forEach { char ->
            when (char) {
              '\\' -> append("\\\\")
              '"' -> append("\\\"")
              '$' -> append("\\$")
              else ->
                if (char.code < 32 || char.code == 127)
                  append("\\u" + char.code.toString(16).padStart(4, '0'))
                else append(char)
            }
          }
          append('"')
        }
      ScalarType.BOOLEAN -> value.boolean.toString()
      ScalarType.INT -> value.int.toString()
      ScalarType.LONG ->
        if (value.long == Long.MIN_VALUE) "(-9223372036854775807L - 1L)"
        else value.long.toString() + "L"
      ScalarType.FLOAT -> value.float.toString() + "f"
      ScalarType.DOUBLE -> value.double.toString()
    }

  private fun helperName(entry: ProductionEntryPoint): String =
    "UidBody" +
      MessageDigest.getInstance("SHA-256")
        .digest(entry.kotlinFunction.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

  private fun helperFqn(entry: ProductionEntryPoint): String =
    entry.kotlinFunction.substringBeforeLast('.') + "." + helperName(entry)
}
