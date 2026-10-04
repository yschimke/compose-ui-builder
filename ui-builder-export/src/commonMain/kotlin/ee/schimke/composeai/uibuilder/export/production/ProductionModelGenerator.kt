package ee.schimke.composeai.uibuilder.export.production

data class ProductionGeneratedFile(val path: String, val source: String)

/**
 * Emits only explicitly owned data models. Compose emission remains with the shared generator.
 * External mappings are validated declarations, not reflected Kotlin classes or generated copies.
 */
object ProductionModelGenerator {
  fun generate(contract: ValidatedProductionContract): List<ProductionGeneratedFile> =
    contract.models.values
      .filter { it.ownership == ModelOwnership.GENERATED }
      .sortedBy { it.kotlinType }
      .map { model ->
        ProductionGeneratedFile(
          path = model.kotlinType.replace('.', '/') + ".kt",
          source =
            buildString {
              appendLine("// Generated from a project-owned .uid contract. Do not edit.")
              appendLine("package ${model.kotlinType.substringBeforeLast('.')}")
              appendLine()
              appendLine("public data class ${model.kotlinType.substringAfterLast('.')}(")
              model.fields.forEach { field ->
                appendLine("  public val ${field.name}: ${kotlinType(field.type, contract)},")
              }
              appendLine(")")
            },
        )
      }

  private fun kotlinType(type: ProductionType, contract: ValidatedProductionContract): String =
    when (type) {
      is ProductionType.Scalar -> type.scalar.kotlinType
      is ProductionType.Model -> contract.models.getValue(type.modelId).kotlinType
      is ProductionType.ListType -> "kotlin.collections.List<${kotlinType(type.element, contract)}>"
    } + if (type.nullable) "?" else ""
}
