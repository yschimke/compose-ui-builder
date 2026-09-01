@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The explicitly enabled production catalogs admitted by the v1 service.
 *
 * Resolution is exact across all four pin fields. The packaged catalog is parsed strictly and its
 * invariants are checked before it is exposed; there is no "closest" revision or permissive
 * component fallback. Export capabilities are supplied by the renderer adapter at startup, so a
 * host without the packaged daemon lane advertises Compose only instead of claiming artifacts it
 * cannot produce.
 */
class CurrentM3UiBuilderCatalogExecutor(
  source: String = packagedM3CatalogSource(),
  catalogSystemIds: Set<String> = setOf(DEFAULT_CATALOG_SYSTEM_ID),
  exportCapabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
) : UiBuilderCatalogExecutor {
  private val baseCatalog =
    json
      .decodeFromString<CatalogCapabilityV1>(source)
      .let(::validateCatalog)
      .copy(exportCapabilities = exportCapabilities)
  private val availableCatalogs =
    mapOf(
      DEFAULT_CATALOG_SYSTEM_ID to baseCatalog,
      REMOTE_M3_CATALOG_SYSTEM_ID to remoteM3Catalog(baseCatalog),
    )
  private val catalogs =
    catalogSystemIds
      .also { require(it.isNotEmpty()) { "at least one UI-builder catalog must be enabled" } }
      .associateWith { systemId ->
        require(SAFE_SYSTEM_ID.matches(systemId)) { "invalid UI-builder catalog id: $systemId" }
        requireNotNull(availableCatalogs[systemId]) {
          "UI-builder catalog $systemId has no packaged adapter"
        }
      }
  private val references = catalogs.mapValues { (_, catalog) ->
    CatalogReferenceV1(
      systemId = catalog.benchmark.catalogSystemId,
      catalogRevision = catalog.benchmark.catalogRevision,
      // The frozen v1 fixture names this pin explicitly. When the catalog wire shape grows a
      // digest field, this becomes the digest read from the signed catalog rather than a
      // convention.
      capabilityDigest = CURRENT_CAPABILITY_DIGEST,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )
  }
  private val components = catalogs.mapValues { (_, catalog) ->
    catalog.components.associateBy { it.componentId }
  }

  override fun listCatalogs(): List<CatalogCapabilityV1> = catalogs.values.toList()

  override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
    catalogs[reference.systemId]?.takeIf { reference == references[reference.systemId] }

  override fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue? {
    val systemId = catalog.benchmark.catalogSystemId
    if (catalog != catalogs[systemId])
      return issue("CATALOG_MISMATCH", "catalog is not an enabled UI-builder catalog")
    if (document.catalogPin != references[systemId]) {
      return issue("CATALOG_PIN_MISMATCH", "document catalog pin does not resolve exactly")
    }
    val catalogComponents = components.getValue(systemId)
    val encodedDocument = json.encodeToJsonElement(document).jsonObject
    val encodedNodes = encodedDocument.getValue("nodes").jsonObject
    for ((nodeId, nodeElement) in encodedNodes.entries.sortedBy { it.key }) {
      val node = nodeElement.jsonObject
      val componentId = node.requiredString("componentId")
      val component =
        catalogComponents[componentId]
          ?: return issue(
            "UNKNOWN_COMPONENT",
            "component $componentId is not in $systemId",
            nodeId,
          )
      val properties = node.objectOrEmpty("properties")
      val declaredProperties = component.properties.associateBy { it.name }
      for ((name, value) in properties) {
        val capability =
          declaredProperties[name]
            ?: return issue(
              "UNKNOWN_PROPERTY",
              "property $name is not declared by $componentId",
              nodeId,
              name,
            )
        val unwrapped = value.unwrapTypedValue()
        if (!capability.jsonType.accepts(unwrapped)) {
          return issue(
            "INVALID_PROPERTY_TYPE",
            "property $name does not match its catalog JSON type",
            nodeId,
            name,
          )
        }
        if (capability.allowedValues.isNotEmpty() && unwrapped !in capability.allowedValues) {
          return issue(
            "INVALID_PROPERTY_VALUE",
            "property $name is outside its catalog allowed values",
            nodeId,
            name,
          )
        }
      }
      component.properties
        .filter { it.required }
        .forEach { property ->
          if (property.name !in properties) {
            return issue(
              "MISSING_REQUIRED_PROPERTY",
              "required property ${property.name} is missing",
              nodeId,
              property.name,
            )
          }
        }

      val allowedModifiers = component.modifierCapabilities.toSet()
      node.arrayOrEmpty("modifiers").forEachIndexed { index, modifier ->
        val type = (modifier as? JsonObject)?.optionalString("type")
        if (type == null || type !in allowedModifiers) {
          return issue(
            "UNKNOWN_MODIFIER",
            "modifier ${type ?: "at index $index"} is not declared by $componentId",
            nodeId,
            "modifiers[$index]",
          )
        }
      }

      val declaredSlots = component.slots.associateBy { it.name }
      val acceptsDynamicSlots = "DynamicSlots" in component.traits
      val slots = node.objectOrEmpty("slots")
      for ((name, childrenElement) in slots) {
        val slot =
          declaredSlots[name]
            ?: if (acceptsDynamicSlots) null
            else
              return issue(
                "UNKNOWN_SLOT",
                "slot $name is not declared by $componentId",
                nodeId,
                name,
              )
        val children = childrenElement.jsonArray.map { it.jsonPrimitive.content }
        val maximum = slot?.cardinality?.max
        if (
          slot != null &&
            (children.size < slot.cardinality.min || (maximum != null && children.size > maximum))
        ) {
          return issue(
            "SLOT_CARDINALITY",
            "slot $name has ${children.size} children; expected ${slot.cardinality.min}..${maximum ?: "unbounded"}",
            nodeId,
            name,
          )
        }
        for (childId in children) {
          val child =
            encodedNodes[childId]?.jsonObject
              ?: return issue(
                "UNKNOWN_CHILD",
                "slot $name references missing node $childId",
                nodeId,
                name,
              )
          val childCapability =
            catalogComponents[child.requiredString("componentId")]
              ?: return issue(
                "UNKNOWN_COMPONENT",
                "child $childId has an unknown component",
                childId,
              )
          val roleAccepted =
            slot == null ||
              slot.acceptedRoles.isEmpty() ||
              childCapability.role in slot.acceptedRoles
          val traitAccepted =
            slot == null ||
              slot.acceptedTraits.isEmpty() ||
              "AnyContent" in slot.acceptedTraits ||
              childCapability.traits.any(slot.acceptedTraits::contains)
          if (!roleAccepted && !traitAccepted) {
            return issue(
              "INCOMPATIBLE_SLOT_CHILD",
              "child $childId is not compatible with slot $name",
              nodeId,
              name,
            )
          }
        }
      }
      component.slots
        .filter { it.name !in slots }
        .forEach { slot ->
          if (slot.cardinality.min > 0) {
            return issue(
              "SLOT_CARDINALITY",
              "required slot ${slot.name} is missing",
              nodeId,
              slot.name,
            )
          }
        }
    }
    return null
  }

  companion object {
    const val RESOURCE: String = "/ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json"
    const val CURRENT_CAPABILITY_DIGEST: String = "candidate"
    const val DEFAULT_CATALOG_SYSTEM_ID: String = "m3-catalog"
    const val REMOTE_M3_CATALOG_SYSTEM_ID: String = "remote-m3"
    private val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private fun packagedM3CatalogSource(): String =
      checkNotNull(CurrentM3UiBuilderCatalogExecutor::class.java.getResourceAsStream(RESOURCE)) {
          "packaged M3 UI-builder catalog is missing"
        }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
  }
}

/**
 * The first Remote M3 authoring surface is deliberately a reviewed subset, not an alias for the
 * complete Material 3 catalog. The host dimensions copy the stable 240dp-screen squircle preview
 * contract from wear-m3-catalog: 200x60dp or 200x108dp content, 8dp padding on every edge and a
 * 26dp corner radius, producing 216x76dp and 216x124dp canvases.
 */
private fun remoteM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  val contentSlot =
    box.slots
      .single()
      .copy(
        name = "content",
        cardinality = box.slots.single().cardinality.copy(min = 0, max = 1),
      )
  fun widget(componentId: String, displayName: String) =
    box.copy(
      componentId = componentId,
      displayName = displayName,
      role = "Scaffold",
      traits = listOf("ScreenContent", "WearWidgetHost", "RemoteContentHost"),
      slots = listOf(contentSlot),
      properties = emptyList(),
      modifierCapabilities = emptyList(),
      wasm =
        supportedWasm.copy(
          notes =
            "Compose UI recreation of the Glance Wear squircle host preview; its content slot may host ordinary or nested Remote Compose content."
        ),
      code = null,
      svg =
        blockedSvg?.copy(
          notes = "The copied Wear widget host geometry has not yet passed structured SVG parity."
        ),
    )
  val authoringIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "m3/surface",
      "m3/text",
      "remote-compose/document",
    )
  return base.copy(
    benchmark =
      base.benchmark.copy(
        id = "remote-m3-wear-widget-scaffolds",
        sourceRevision = "wear-m3-catalog@d4e4e684e61d0657aad4ccb7752b8c0ab5d9dedf",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
        catalogRevision = "wear-widget-scaffolds-v1",
      ),
    components =
      listOf(
        widget("remote-m3/widget-container-small", "Wear widget · Small (216×76dp)"),
        widget("remote-m3/widget-container-large", "Wear widget · Large (216×124dp)"),
      ) + authoringIds.map(components::getValue),
  )
}

/** Deterministic, revision-pinned Compose source projection for the strict current catalog. */
class RevisionPinnedComposeExportExecutor : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    require(request.format == ExportFormatV1.COMPOSE) {
      "${request.format} export is unsupported by the production executor"
    }
    require(request.revision == request.document.revision) { "export revision/document mismatch" }
    require(request.document.id == request.designId) { "export design/document mismatch" }
    val source = ComposeSourceProjection(request).render()
    return ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = source,
      contentDigest = source.sha256(),
      diagnostics =
        listOf(
          ExportDiagnosticV1(
            severity = DiagnosticSeverityV1.WARNING,
            code = "ALMOST_COMPILING_PROJECTION",
            message =
              "Catalog symbols and the complete typed document are preserved; project-specific state and event adapters may require edits.",
          )
        ),
    )
  }
}

/** Immutable, renderer-neutral request for one exact saved document revision. */
data class UiBuilderRenderRequest(
  val designId: String,
  val revision: Long,
  val documentHash: String,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val localeTag: String,
  val fontScale: Float,
  val encodedDocument: String,
)

/** Narrow pixel/vector port implemented by the server beside its render-host dependency. */
interface UiBuilderRenderPort : Closeable {
  val supportsSvg: Boolean

  fun renderPng(request: UiBuilderRenderRequest): ByteArray

  fun renderSvg(request: UiBuilderRenderRequest): ByteArray
}

/** Combines the runtime-owned Compose projection with an injected renderer-neutral port. */
class ProductionUiBuilderExportExecutor(
  private val renderer: UiBuilderRenderPort,
  private val compose: UiBuilderExportExecutor = RevisionPinnedComposeExportExecutor(),
) : UiBuilderExportExecutor, Closeable {
  val capabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1(composeCode = true, svg = renderer.supportsSvg, png = true)

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 =
    when (request.format) {
      ExportFormatV1.COMPOSE -> compose.export(request)
      ExportFormatV1.PNG -> request.binaryArtifact(renderer.renderPng(request.toRenderRequest()))
      ExportFormatV1.SVG -> request.svgArtifact(renderer.renderSvg(request.toRenderRequest()))
    }

  override fun close() = renderer.close()

  private fun RevisionPinnedUiBuilderExport.toRenderRequest(): UiBuilderRenderRequest =
    UiBuilderRenderRequest(
      designId = designId,
      revision = revision,
      documentHash = documentHash,
      widthPx = (document.environment.widthDp * document.environment.density).toInt(),
      heightPx = (document.environment.heightDp * document.environment.density).toInt(),
      density = document.environment.density.toFloat(),
      localeTag = document.environment.locale,
      fontScale = document.environment.fontScale.toFloat(),
      encodedDocument = projectRendererDocument(document),
    )

  private fun RevisionPinnedUiBuilderExport.binaryArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.PNG,
      mediaType = "image/png",
      encoding = ExportEncodingV1.BASE64,
      content = Base64.getEncoder().encodeToString(bytes),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.svgArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.SVG,
      mediaType = "image/svg+xml; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = bytes.toString(Charsets.UTF_8),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.provenanceDiagnostics(): List<ExportDiagnosticV1> =
    listOf(
      ExportDiagnosticV1(
        severity = DiagnosticSeverityV1.INFO,
        code = "REVISION_PINNED_DAEMON_RENDER",
        message =
          "Rendered design $designId revision $revision ($documentHash) through the packaged Compose UI-builder preview.",
      )
    )
}

/** Runtime-owned opaque preview bundle; materialization and rendering stay outside this module. */
object PackagedUiBuilderRenderBundle {
  const val RESOURCE: String =
    "/ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
  const val PREVIEW_ID: String =
    "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
  const val DOCUMENT_OVERRIDE_KEY: String = "uiBuilder.document.v1"

  fun copyTo(root: Path): Path {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
          "packaged UI-builder renderer bundle is missing"
        }
        .use { it.readBytes() }
    val generation = root.toAbsolutePath().normalize().resolve(bytes.sha256())
    Files.createDirectories(generation)
    val bundle = generation.resolve("ui-builder-renderer.bundle.png")
    if (!Files.exists(bundle) || !Files.readAllBytes(bundle).contentEquals(bytes)) {
      val partial = Files.createTempFile(generation, ".ui-builder-renderer.", ".tmp")
      try {
        Files.write(partial, bytes)
        Files.move(
          partial,
          bundle,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        Files.deleteIfExists(partial)
      }
    }
    return bundle
  }
}

/** Canonical, loss-checked protocol → renderer wire projection used by the named override. */
fun projectRendererDocument(document: DesignDocumentV1): String {
  require(document.revision in 0..Int.MAX_VALUE.toLong()) {
    "renderer revision is outside the v1 Int range: ${document.revision}"
  }
  val source = json.encodeToJsonElement(document).jsonObject
  val projectedNodes =
    JsonObject(
      source
        .getValue("nodes")
        .jsonObject
        .entries
        .sortedBy { it.key }
        .associate { (id, value) ->
          val node = value.jsonObject
          id to
            JsonObject(
              linkedMapOf(
                "id" to node.getValue("id"),
                "componentId" to node.getValue("componentId"),
                "properties" to (node["properties"] ?: JsonObject(emptyMap())),
                "modifiers" to (node["modifiers"] ?: JsonArray(emptyList())),
                "slots" to (node["slots"] ?: JsonObject(emptyMap())),
                "eventBindings" to (node["eventBindings"] ?: JsonObject(emptyMap())),
              )
            )
        }
    )
  val projected =
    JsonObject(
      linkedMapOf(
        "schema" to source.getValue("schema"),
        "id" to source.getValue("id"),
        "title" to source.getValue("title"),
        "revision" to JsonPrimitive(document.revision.toInt()),
        "catalogPin" to source.getValue("catalogPin"),
        "environment" to source.getValue("environment"),
        "stateVariables" to (source["stateVariables"] ?: JsonObject(emptyMap())),
        "roots" to source.getValue("roots"),
        "nodes" to projectedNodes,
      )
    )
  return canonicalJson(projected)
}

private class ComposeSourceProjection(private val request: RevisionPinnedUiBuilderExport) {
  private val documentJson = json.encodeToJsonElement(request.document).jsonObject
  private val nodes = documentJson.getValue("nodes").jsonObject
  private val components = request.catalog.components.associateBy { it.componentId }
  private val output = StringBuilder()

  fun render(): String {
    output.appendLine("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)")
    output.appendLine()
    output.appendLine("package generated.uibuilder")
    output.appendLine()
    output.appendLine("import androidx.compose.runtime.Composable")
    output.appendLine("import androidx.compose.ui.Modifier")
    request.catalog.components
      .flatMap { it.code?.imports.orEmpty() }
      .distinct()
      .sorted()
      .forEach { output.appendLine("import $it") }
    output.appendLine()
    output.appendLine("// Design ${request.designId.escapeComment()} revision ${request.revision}")
    output.appendLine("// Document SHA-256: ${request.documentHash}")
    output.appendLine(
      "// Catalog ${request.document.catalogPin.systemId}@${request.document.catalogPin.catalogRevision}; capability ${request.document.catalogPin.capabilityDigest}"
    )
    output.appendLine("// Canonical typed document: ${canonicalJson(documentJson).escapeComment()}")
    output.appendLine("@Composable")
    output.appendLine("fun ${request.document.title.identifier()}() {")
    request.document.roots.forEach { emitNode(it, 1) }
    output.appendLine("}")
    return output.toString()
  }

  private fun emitNode(nodeId: String, level: Int) {
    val node = nodes.getValue(nodeId).jsonObject
    val componentId = node.requiredString("componentId")
    val capability = checkNotNull(components[componentId])
    val symbol = checkNotNull(capability.code?.symbol) { "$componentId has no code capability" }
    line(level, "// node:${nodeId.escapeComment()} component:${componentId.escapeComment()}")
    line(
      level,
      "// typed-properties:${canonicalJson(node.objectOrEmpty("properties")).escapeComment()}",
    )
    val modifiers = node.arrayOrEmpty("modifiers").modifierExpression()
    val properties =
      node
        .objectOrEmpty("properties")
        .entries
        .sortedBy { it.key }
        .map { (name, value) -> "${name.identifier()} = ${value.composeLiteral()}" }
    val slots = node.objectOrEmpty("slots").entries.sortedBy { it.key }
    val arguments = (listOf("modifier = $modifiers") + properties).joinToString(", ")
    if (slots.isEmpty()) {
      line(level, "$symbol($arguments)")
      return
    }
    line(level, "$symbol(")
    line(level + 1, "$arguments,")
    slots.forEach { (name, children) ->
      line(level + 1, "${name.identifier()} = {")
      children.jsonArray.forEach { emitNode(it.jsonPrimitive.content, level + 2) }
      line(level + 1, "},")
    }
    line(level, ")")
  }

  private fun line(level: Int, text: String) {
    repeat(level) { output.append("  ") }
    output.appendLine(text)
  }
}

private fun validateCatalog(catalog: CatalogCapabilityV1): CatalogCapabilityV1 {
  require(catalog.schema.isNotBlank()) { "catalog schema must not be blank" }
  require(catalog.benchmark.catalogSystemId == "m3-catalog") { "unexpected catalog system" }
  require(catalog.benchmark.catalogRevision == "candidate") { "unexpected catalog revision" }
  require(catalog.benchmark.nativeRuntimeId == "candidate") { "unexpected native runtime" }
  require(catalog.components.isNotEmpty()) { "catalog must contain components" }
  require(catalog.components.map { it.componentId }.distinct().size == catalog.components.size) {
    "catalog component ids must be unique"
  }
  catalog.components.forEach { component ->
    require(component.componentId.isNotBlank()) { "component id must not be blank" }
    require(component.slots.map { it.name }.distinct().size == component.slots.size) {
      "duplicate slot in ${component.componentId}"
    }
    require(component.properties.map { it.name }.distinct().size == component.properties.size) {
      "duplicate property in ${component.componentId}"
    }
    component.slots.forEach { slot ->
      require(slot.cardinality.min >= 0) { "negative slot minimum" }
      require(slot.cardinality.max == null || slot.cardinality.max!! >= slot.cardinality.min) {
        "invalid slot maximum"
      }
    }
  }
  return catalog
}

private fun issue(
  code: String,
  message: String,
  nodeId: String? = null,
  field: String? = null,
): UiBuilderCatalogIssue = UiBuilderCatalogIssue(code, message, nodeId, field)

private val json = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

private fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be text" }

private fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.objectOrEmpty(name: String): JsonObject =
  this[name] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
  this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.unwrapTypedValue(): JsonElement {
  val objectValue = this as? JsonObject ?: return this
  return objectValue["value"] ?: objectValue
}

private fun JsonElement.accepts(value: JsonElement): Boolean {
  val names =
    if (this is JsonArray) map { it.jsonPrimitive.content } else listOf(jsonPrimitive.content)
  return names.any { name ->
    when (name) {
      "null" -> value is JsonNull
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
      "number" -> value is JsonPrimitive && value.doubleOrNull != null
      "integer" -> value is JsonPrimitive && value.doubleOrNull?.rem(1.0) == 0.0
      "array" -> value is JsonArray
      "object" -> value is JsonObject
      else -> false
    }
  }
}

private fun JsonArray.modifierExpression(): String {
  var expression = "Modifier"
  for (element in this) {
    val modifier = element.jsonObject
    expression +=
      when (modifier.requiredString("type")) {
        "fillMaxSize" -> ".fillMaxSize()"
        "fillMaxWidth" -> ".fillMaxWidth()"
        "matchParentSize" -> ".matchParentSize()"
        "padding" ->
          ".padding(start = ${modifier.number("startDp")}.dp, top = ${modifier.number("topDp")}.dp, end = ${modifier.number("endDp")}.dp, bottom = ${modifier.number("bottomDp")}.dp)"
        "size" -> {
          val fallback = modifier.double("sizeDp")
          ".size(${modifier.number("widthDp", fallback)}.dp, ${modifier.number("heightDp", fallback)}.dp)"
        }
        "clip" -> ".clip(MaterialTheme.shapes.${modifier.optionalString("shape") ?: "medium"})"
        else -> error("validated modifier emitter drift")
      }
  }
  return expression
}

private fun JsonObject.number(name: String, default: Double = 0.0): String =
  (this[name] as? JsonPrimitive)?.doubleOrNull?.let { value ->
    if (value.rem(1.0) == 0.0) value.toLong().toString() else value.toString()
  } ?: default.toString()

private fun JsonObject.double(name: String, default: Double = 0.0): Double =
  (this[name] as? JsonPrimitive)?.doubleOrNull ?: default

private fun JsonElement.composeLiteral(): String {
  val typed = this as? JsonObject ?: return kotlinLiteral()
  val type = typed.optionalString("type")
  val value = typed["value"]
  return when (type) {
    "colorToken" -> "MaterialTheme.colorScheme.${value?.jsonPrimitive?.content?.identifier()}"
    "typographyToken" -> "MaterialTheme.typography.${value?.jsonPrimitive?.content?.identifier()}"
    "shapeToken" -> "MaterialTheme.shapes.${value?.jsonPrimitive?.content?.identifier()}"
    "state" -> typed.optionalString("variable")?.identifier() ?: "Unit"
    "assetKey" -> value.kotlinLiteral()
    else -> (value ?: typed).kotlinLiteral()
  }
}

private fun JsonElement?.kotlinLiteral(): String =
  when (this) {
    null,
    JsonNull -> "null"
    is JsonPrimitive -> if (isString) "\"${content.escapeKotlin()}\"" else content
    is JsonArray -> joinToString(prefix = "listOf(", postfix = ")") { it.kotlinLiteral() }
    is JsonObject ->
      entries
        .sortedBy { it.key }
        .joinToString(prefix = "mapOf(", postfix = ")") { (key, value) ->
          "\"${key.escapeKotlin()}\" to ${value.kotlinLiteral()}"
        }
  }

private fun canonicalJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
    is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonPrimitive -> element.toString()
  }

private fun String.identifier(): String {
  val words = split(Regex("[^A-Za-z0-9_]+")).filter(String::isNotEmpty)
  val candidate =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar { it.lowercase() }
        else word.replaceFirstChar { it.uppercase() }
      }
      .joinToString("")
      .ifEmpty { "GeneratedDesign" }
  val safe = if (candidate.first().isDigit()) "_$candidate" else candidate
  return if (safe in KOTLIN_KEYWORDS) "`${safe}`" else safe
}

private fun String.escapeKotlin(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private fun String.escapeComment(): String =
  replace("\n", " ").replace("\r", " ").replace("*/", "* /")

private fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

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
