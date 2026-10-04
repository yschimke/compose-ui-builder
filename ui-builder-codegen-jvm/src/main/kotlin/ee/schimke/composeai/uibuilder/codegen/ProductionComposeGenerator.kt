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

  private data class Read(val path: List<String>, val type: ProductionType.Scalar)

  private data class Body(
    val entry: ProductionEntryPoint,
    val reads: List<Read>,
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
      check(
        entry.events.isEmpty(),
        "event declarations need explicit event lowering, which this generator does not yet support",
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
      val reads = mutableListOf<Read>()
      val nodes = design.nodes.toMutableMap()
      entry.bindings.forEach { binding ->
        val type = binding.expectedType as? ProductionType.Scalar
        check(
          type != null && !type.nullable,
          "${binding.nodeId}.${binding.property}: only non-null scalar reads are supported",
        )
        val parameter = "p${reads.size}"
        reads += Read(binding.path, requireNotNull(type))
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
      val overrides = linkedMapOf<String, ScreenNode>()
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
          val arguments =
            linkedMapOf<String, ScreenValue>(
              "modifier" to ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER)
            )
          child.reads.forEachIndexed { index, read ->
            val parameter = "p${reads.size}"
            reads += read.copy(path = use.dataPath + read.path)
            arguments["p$index"] = ScreenValue.ParameterRead(parameter, read.type.scalar.kotlinType)
          }
          overrides[use.nodeId] =
            ScreenNode(componentId = helperFqn(child.entry), arguments = arguments)
          child
        }
      val typed = design.copy(nodes = nodes).toDesignDocumentV1()
      val projected = ScreenDocumentProjection.projectProduction(typed, nodeOverrides = overrides)
      check(
        projected is ScreenDocumentProjection.Outcome.Projected,
        (projected as? ScreenDocumentProjection.Outcome.Refused)?.reasons?.joinToString("; ")
          ?: "projection failed",
      )
      projected as ScreenDocumentProjection.Outcome.Projected
      check(projected.assetPlaceholders.isEmpty(), "asset placeholders are not production output")
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
      return Body(entry, reads, root, combined).also { bodies[id] = it }
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
                }
            val placeholders =
              linkedMapOf<String, ScreenValue>(
                "modifier" to ScreenValue.Reference(MODIFIER, typeFqn = MODIFIER)
              )
            body.reads.forEachIndexed { index, read ->
              placeholders["p$index"] = literal(read.type.scalar)
            }
            val screen =
              ScreenDocument(
                "UidValidationHost",
                ScreenNode("", function = name, arguments = placeholders),
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
            val privateToken =
              requireNotNull(function.modifierList?.getModifier(KtTokens.PRIVATE_KEYWORD))
            val offset = privateToken.textRange.startOffset - function.textRange.startOffset
            val helper =
              function.text.replaceRange(offset, offset + privateToken.textLength, "internal")
            val source = buildString {
              appendLine("// Generated from an opt-in project-owned .uid contract. Do not edit.")
              appendLine(file.packageDirective!!.text)
              appendLine(file.importList!!.text)
              appendLine()
              appendLine(helper)
              appendLine()
              appendLine("@androidx.compose.runtime.Composable")
              appendLine(
                "${entry.visibility.name.lowercase()} fun ${entry.kotlinFunction.substringAfterLast('.')}("
              )
              appendLine("  data: ${contract.models.getValue(entry.inputModel).kotlinType},")
              appendLine("  modifier: $MODIFIER = $MODIFIER,")
              appendLine(") {")
              appendLine("  $name(")
              appendLine("    modifier = modifier,")
              body.reads.forEachIndexed { index, read ->
                appendLine(
                  "    p$index = ${readExpression(contract, entry.inputModel, read.path)},"
                )
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

  private fun readExpression(
    contract: ValidatedProductionContract,
    modelId: String,
    path: List<String>,
  ): String {
    var model = contract.models.getValue(modelId)
    return "data" +
      path.joinToString("") { fieldName ->
        val field = model.fields.single { it.name == fieldName }
        (field.type as? ProductionType.Model)?.let { model = contract.models.getValue(it.modelId) }
        ".${field.property ?: field.name}"
      }
  }

  private fun helperName(entry: ProductionEntryPoint): String =
    "UidBody" +
      MessageDigest.getInstance("SHA-256")
        .digest(entry.kotlinFunction.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

  private fun helperFqn(entry: ProductionEntryPoint): String =
    entry.kotlinFunction.substringBeforeLast('.') + "." + helperName(entry)

  private fun literal(type: ScalarType): ScreenValue =
    when (type) {
      ScalarType.STRING -> ScreenValue.Text("")
      ScalarType.BOOLEAN -> ScreenValue.Bool(false)
      ScalarType.INT -> ScreenValue.Whole(0)
      ScalarType.LONG -> ScreenValue.Whole(0)
      ScalarType.FLOAT -> ScreenValue.Fractional32(0f)
      ScalarType.DOUBLE -> ScreenValue.Fractional(0.0)
    }
}
