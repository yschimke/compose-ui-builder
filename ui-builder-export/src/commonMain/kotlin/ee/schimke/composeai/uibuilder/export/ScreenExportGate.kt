package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * The one answer to "would the Compose export refuse this design, and why?", shared by the server's
 * export and the editor's problems panel (possible since `screen-model` 1.77.0 is multiplatform),
 * so the panel cannot miss a refusal.
 *
 * [EXPRESSION_PACKAGES] is a security guard: the generator refuses references outside it. Keeping
 * it (and [PACKAGE_NAME]) here means no second caller can widen it without review.
 */
object ScreenExportGate {

  /** The packages a generated screen may name. Narrow on purpose; widening is a reviewed act. */
  // `kotlin.math` for nonnegative bound spacing; `kotlin.collections` for gradient colour lists;
  // `kotlin.io.encoding` (the Base64 codec only — subpackages match, parents don't) for Play
  // services' font certificates; ranges, text and Int for list-detail destination role mapping.
  val EXPRESSION_PACKAGES: Set<String> =
    setOf(
      "androidx.compose",
      "kotlin.math",
      "kotlin.ranges",
      "kotlin.text",
      "kotlin.Int",
      "kotlin.collections",
      "kotlin.io.encoding",
    )

  /** The package a generated screen is emitted into. */
  const val PACKAGE_NAME: String = "generated.uibuilder"

  /**
   * The whole answer: the problems panel wants [Refused.reasons], the code pane [Emitted.source],
   * and neither should run the generator twice.
   */
  sealed interface Outcome {
    /** The Kotlin the export would write. */
    data class Emitted(val source: String) : Outcome

    /** Every unexpressible thing found, not the first — a builder wants the whole list. */
    data class Refused(val reasons: List<String>) : Outcome
  }

  /**
   * What the export would produce for [document], or why it would refuse. [tagNodes] adds
   * `Modifier.testTag("<nodeId>")` to every node — only for the native preview lane.
   */
  fun export(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    tagNodes: Boolean = false,
    /**
     * The font API theme typefaces are written with; the code pane keeps Android's `GoogleFont`.
     * See [TypefaceTarget].
     */
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
  ): Outcome = export(document, record, tagNodes, typefaces, VariableFontExport.None)

  /**
   * [export], writing each variable font text's declaration with [variableFonts] and joining it
   * into the file. [VariableFontExport.None] (the wasm code pane) leaves a note instead. An
   * overload to keep the published JVM descriptor above.
   */
  fun export(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    tagNodes: Boolean = false,
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
    variableFonts: VariableFontExport,
  ): Outcome = export(document, record, emptyMap(), tagNodes, typefaces, variableFonts)

  /**
   * [export], lowering each component [exportProjections] names through the projection its catalog
   * publishes (`exportProjection` on the component's policy). The packages the generated source may
   * name widen by exactly the owners those projections print, and only by owners in a package the
   * catalog's own [record] already calls into: a projection can name `ButtonDefaults` beside
   * `Button`, never an arbitrary class. An overload to keep the published JVM descriptors above.
   */
  fun export(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    exportProjections: Map<String, ExportProjection>,
    tagNodes: Boolean = false,
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
    variableFonts: VariableFontExport = VariableFontExport.None,
  ): Outcome = export(document, record, exportProjections, null, tagNodes, typefaces, variableFonts)

  /**
   * [export], with the catalog's [structure]: its `screen-root` frame written around the root, the
   * locals that frame declares, and the item scope a projection's DSL item member opens. An
   * overload to keep the published JVM descriptors above.
   */
  fun export(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    exportProjections: Map<String, ExportProjection>,
    structure: CatalogScreenStructure?,
    tagNodes: Boolean = false,
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
    variableFonts: VariableFontExport = VariableFontExport.None,
  ): Outcome {
    if (record == null) {
      // Named rather than silent: "this host has no record for the catalog" is a different problem
      // from "this design is unexpressible", and only the first is fixed by configuration.
      return Outcome.Refused(
        listOf("no component record is configured for this catalog, so nothing can be exported")
      )
    }
    val resolvable = record.callableAliases()
    val unwrapped =
      when (val unwrapped = ScreenRootFrame.unwrap(document, structure)) {
        is ScreenRootFrame.Unwrapped.Refused -> return Outcome.Refused(unwrapped.reasons)
        is ScreenRootFrame.Unwrapped.Document -> unwrapped
      }
    val framed = unwrapped.document
    return when (
      val projected =
        ScreenDocumentProjection.project(
          framed,
          exportProjections,
          tagNodes = tagNodes,
          typefaces = typefaces,
          slotReceivers = slotReceivers(exportProjections, record),
          structure =
            ProjectedStructure(
              dslReceivers(exportProjections, record),
              structure?.itemScope,
              structure?.stringResources,
              unwrapped.frameSlots,
            ),
        )
    ) {
      // Report both classes of refusal: projection and generator refuse for unrelated reasons, and
      // sequencing them hid the second list until the first was empty.
      is ScreenDocumentProjection.Outcome.Refused ->
        Outcome.Refused(
          (projected.reasons + unprovenComponents(framed, record, exportProjections.keys))
            .distinct()
        )
      is ScreenDocumentProjection.Outcome.Projected ->
        when (
          val generated =
            ScreenGenerator.generate(
              projected.document,
              projected.resolvable(resolvable),
              PACKAGE_NAME,
              EXPRESSION_PACKAGES +
                projectedOwners(exportProjections, record) +
                // A scope a projected record hands its children is theirs to call members of
                // (`ButtonGroupScope.weight`), as `RowScope`'s are inside a `Row`.
                slotReceivers(exportProjections, record).values.flatMap { it.values } +
                stringResourcePackages(structure),
              locals = structure?.locals.orEmpty(),
              frame =
                (unwrapped.frame ?: structure?.frame)?.let {
                  ScreenGenerator.Frame(it, structure?.imports.orEmpty())
                },
              output = ScreenGenerator.Output.SCREEN,
              frameSlots = projected.frameSlots,
            )
        ) {
          is ScreenGenerator.Result.Refused -> Outcome.Refused(generated.reasons)
          is ScreenGenerator.Result.Emitted ->
            when (
              val joined =
                variableFonts.join(generated.source, projected.variableFontTexts, PACKAGE_NAME)
            ) {
              is VariableFontExport.Joined.Emitted -> Outcome.Emitted(joined.source)
              is VariableFontExport.Joined.Refused -> Outcome.Refused(joined.reasons)
            }
        }
    }
  }

  /**
   * The owners [exportProjections] print that sit in a package some component of [record] is
   * declared in. Anything else stays outside the allowlist, so the generator refuses it by name.
   *
   * A list or object property's object class (`Contact(name = …)`) is admitted as itself, the one
   * callable its codec writes, on the same condition; its `listOf` is `kotlin.collections`, which
   * [EXPRESSION_PACKAGES] already admits.
   */
  internal fun projectedOwners(
    exportProjections: Map<String, ExportProjection>,
    record: ComponentRecordFile,
  ): Set<String> {
    if (exportProjections.isEmpty()) return emptySet()
    val packages =
      record.components.map { it.symbol.callable.substringBeforeLast('.', "") }.toSet() - ""
    return (exportProjections.values.flatMap { it.qualifiedOwners } +
        exportProjections.values.flatMap(::objectCallables) +
        exportProjections.values.flatMap(::fixedCallables))
      .filter { owner -> owner.substringBeforeLast('.', "") in packages }
      .toSet()
  }

  /**
   * [document]'s reusable components as a file of `internal` composables and nothing else: no
   * screen function. Each binding a component's body reads is a value parameter named for its key,
   * and each action-free event on a node inside it is a callback named for the node and the event
   * (`onCancelClick`), so code in the same module calls a component the way it would call one it
   * wrote. The document's root, which only places the components to draw them in the builder, is
   * not written.
   */
  fun exportComponents(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    exportProjections: Map<String, ExportProjection> = emptyMap(),
    structure: CatalogScreenStructure? = null,
  ): Outcome {
    if (record == null) {
      return Outcome.Refused(
        listOf("no component record is configured for this catalog, so nothing can be exported")
      )
    }
    return when (
      val projected =
        ScreenDocumentProjection.project(
          document,
          exportProjections,
          slotReceivers = slotReceivers(exportProjections, record),
          structure =
            ProjectedStructure(
              dslReceivers(exportProjections, record),
              structure?.itemScope,
              structure?.stringResources,
            ),
        )
    ) {
      is ScreenDocumentProjection.Outcome.Refused ->
        Outcome.Refused((projected.reasons + unprovenComponents(document, record)).distinct())
      is ScreenDocumentProjection.Outcome.Projected ->
        when (
          val generated =
            ScreenGenerator.generate(
              projected.document,
              projected.resolvable(record.callableAliases()),
              PACKAGE_NAME,
              EXPRESSION_PACKAGES +
                projectedOwners(exportProjections, record) +
                stringResourcePackages(structure),
              locals = structure?.locals.orEmpty(),
              frame = null,
              output = ScreenGenerator.Output.COMPONENTS,
            )
        ) {
          is ScreenGenerator.Result.Refused -> Outcome.Refused(generated.reasons)
          is ScreenGenerator.Result.Emitted -> Outcome.Emitted(generated.source)
        }
    }
  }

  /**
   * What a `stringResource(Res.string.key)` names, widened only when the catalog declares its
   * resource accessor: the resources runtime and the package its generated `Res` lives in.
   */
  internal fun stringResourcePackages(structure: CatalogScreenStructure?): Set<String> {
    val res = structure?.stringResources ?: return emptySet()
    val owner = res.substringBeforeLast('.', "")
    return setOf(COMPOSE_RESOURCES_PACKAGE) + listOf(owner).filter { it.isNotEmpty() }
  }

  /** Where `stringResource` and `StringResource` are declared. */
  internal const val COMPOSE_RESOURCES_PACKAGE: String = "org.jetbrains.compose.resources"

  /** Every object callable [projection]'s list and object codecs write, at any depth. */
  private fun objectCallables(projection: ExportProjection): Set<String> = buildSet {
    fun codec(written: ExportProjection.Codec) {
      when (written) {
        is ExportProjection.Codec.ListOf -> codec(written.element)
        is ExportProjection.Codec.Object -> {
          add(written.callableFqn)
          written.fields.values.forEach { codec(it.codec) }
        }
        else -> Unit
      }
    }
    fun value(read: ExportProjection.Value) {
      when (read) {
        is ExportProjection.Value.Property -> codec(read.codec)
        is ExportProjection.Value.Call -> read.arguments.values.forEach(::value)
        else -> Unit
      }
    }
    for (target in projection.targets) {
      target.parameters.values.forEach(::value)
      target.contexts.values.forEach { context -> context.parameters.values.forEach(::value) }
    }
  }

  /**
   * Each callable a projection's fixed values construct, named as itself: a top-level one's owner
   * is its whole package (`SurfaceTransformation(spec)` would name
   * `androidx.wear.compose.material3`), so admitting the callable, not the owner, keeps the
   * allowlist to exactly what is called.
   */
  private fun fixedCallables(projection: ExportProjection): Set<String> = buildSet {
    fun screen(value: ScreenValue) {
      when (value) {
        is ScreenValue.Construct -> {
          add(value.callableFqn)
          value.positional.forEach(::screen)
          value.named.values.forEach(::screen)
        }
        is ScreenValue.Chain -> {
          screen(value.receiver)
          value.links.forEach { link ->
            link.positional.forEach(::screen)
            link.named.values.forEach(::screen)
          }
        }
        else -> Unit
      }
    }
    fun value(read: ExportProjection.Value) {
      when (read) {
        is ExportProjection.Value.Fixed -> screen(read.value)
        is ExportProjection.Value.Call -> read.arguments.values.forEach(::value)
        else -> Unit
      }
    }
    for (target in projection.targets) {
      target.parameters.values.forEach(::value)
      for (context in target.contexts.values) {
        context.parameters.values.forEach(::value)
        context.modifier.forEach { link ->
          link.positional.forEach(::screen)
          link.named.values.forEach(::screen)
        }
      }
    }
  }

  /** The composable-slot receivers of every record a projection targets, from [record]. */
  private fun slotReceivers(
    exportProjections: Map<String, ExportProjection>,
    record: ComponentRecordFile,
  ): Map<String, Map<String, String>> {
    if (exportProjections.isEmpty()) return emptyMap()
    val targets = exportProjections.values.flatMap { it.targets }.map { it.record }.toSet()
    return record.components
      .filter { it.canonicalId in targets }
      .associate { component ->
        component.canonicalId to
          component.parameters
            .mapNotNull { p -> p.composableSlotReceiver?.let { p.name to it } }
            .toMap()
      }
  }

  /** The DSL receivers of every record a projection targets, from [record]. */
  private fun dslReceivers(
    exportProjections: Map<String, ExportProjection>,
    record: ComponentRecordFile,
  ): Map<String, Map<String, String>> {
    if (exportProjections.isEmpty()) return emptyMap()
    val targets = exportProjections.values.flatMap { it.targets }.map { it.record }.toSet()
    return record.components
      .filter { it.canonicalId in targets }
      .associate { component ->
        component.canonicalId to
          component.parameters.mapNotNull { p -> p.scopeDslReceiver?.let { p.name to it } }.toMap()
      }
  }

  /**
   * Why the export would refuse [document] with [exportProjections], or empty when it would not.
   */
  fun refusals(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    exportProjections: Map<String, ExportProjection>,
  ): List<String> = refusals(document, record, exportProjections, null)

  /** [refusals] with the catalog's [structure], as [export] takes it. */
  fun refusals(
    document: DesignDocumentV1,
    record: ComponentRecordFile?,
    exportProjections: Map<String, ExportProjection>,
    structure: CatalogScreenStructure?,
  ): List<String> =
    when (val outcome = export(document, record, exportProjections, structure)) {
      is Outcome.Refused -> outcome.reasons
      is Outcome.Emitted -> emptyList()
    }

  /**
   * The components in [document] that no record proves a call site for, in the generator's own
   * words (mirroring `ScreenGenerator`'s `ComponentIndex` lookups). Only consulted when the
   * projection already refused. Answers only the call-site question; parameter refusals may still
   * follow.
   */
  private fun unprovenComponents(
    document: DesignDocumentV1,
    record: ComponentRecordFile,
    projected: Set<String> = emptySet(),
  ): List<String> {
    val byCanonical = record.components.groupBy { it.canonicalId }
    val byAlias =
      record.components
        .flatMap { component -> component.componentIds.distinct().map { it to component } }
        .groupBy({ it.first }, { it.second })
    // Document order, deduplicated: one line per component, not one per node. A design with 29
    // icons in it has one icon problem.
    return document.nodes.values
      // A variable font text calls a declaration generated at export, which the projection
      // records itself; see `VariableFontTextRecord`.
      .filter {
        it.component == null &&
          // A projected component is called through its projection's own records, not by id.
          it.componentId !in projected &&
          it.componentId != "layout/for-each" &&
          it.componentId != VariableFontText.M3_ID
      }
      .map { it.componentId }
      .distinct()
      .mapNotNull { id ->
        byCanonical[id]?.let { canonical ->
          return@mapNotNull if (canonical.size == 1) null
          else
            "canonical id `$id` is claimed by ${canonical.size} components in this catalog, so " +
              "it identifies none of them"
        }
        val aliased = byAlias[id] ?: return@mapNotNull "no component `$id` in this catalog"
        if (aliased.size == 1) null
        else
          "catalog id `$id` maps to ${aliased.size} components " +
            "(${aliased.joinToString(", ") { "`${it.canonicalId}`" }}), so it identifies none of " +
            "them"
      }
  }

  /** Why the export would refuse [document], or empty when it would succeed. */
  fun refusals(document: DesignDocumentV1, record: ComponentRecordFile?): List<String> =
    when (val outcome = export(document, record)) {
      is Outcome.Refused -> outcome.reasons
      is Outcome.Emitted -> emptyList()
    }
}

/**
 * [this] with every component also named by its callable — the canonical id without its module.
 *
 * The projection's variant table substitutes callables (`ElevatedCard`), and canonical ids differ
 * by discovering module (`m3-catalog/…` vs `catalog/…`). Aliases are added, never replacing; a
 * collision would be the same Kotlin function anyway.
 */
fun ComponentRecordFile.callableAliases(): ComponentRecordFile {
  val aliased = components.map { component ->
    val callable = component.canonicalId.substringAfter('/', "")
    if (callable.isEmpty() || callable in component.componentIds) component
    else component.newBuilder().also { it.componentIds = component.componentIds + callable }.build()
  }
  return newBuilder().also { it.components = aliased }.build()
}
