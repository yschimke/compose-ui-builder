package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ChainLink
import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ExportProjectionJson
import ee.schimke.composeai.discovery.ScreenValue
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A catalog that publishes an export projection gets the REAL library call: `wear-m3/button` with
 * `variant = filled-tonal` exports as `FilledTonalButton(colors = ButtonDefaults.…)`, from the
 * projection's JSON alone, with nothing in this module knowing what a Wear button is.
 */
class ProjectedComposeExportTest {
  private fun parameter(
    name: String,
    type: String,
    typeFqn: String? = null,
    default: Boolean = false,
    slot: Boolean = false,
    lambda: Boolean = false,
    receiver: String? = null,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.composableSlotReceiver = receiver
        b.hasDefault = default
        b.composableSlot = slot
        if (lambda) b.lambdaReturnTypeFqn = "kotlin.Unit"
      }
      .build()

  private fun record(name: String, componentIds: List<String>, parameters: List<TargetParameter>) =
    ComponentRecord.Builder(
        canonicalId = "wear/androidx.wear.compose.material3.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.${name}Kt",
              callable = "androidx.wear.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.componentIds = componentIds
        b.signatureKnown = true
        b.parameters = parameters
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = if (name.endsWith("Button")) "$name(onClick = {})" else "$name()"
              c.imports = listOf("androidx.wear.compose.material3.$name")
            }
            .build()
      }
      .build()

  private fun button(name: String, ids: List<String>) =
    record(
      name,
      ids,
      listOf(
        parameter("onClick", "() -> Unit", "kotlin.Function0", lambda = true),
        parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
        parameter("enabled", "Boolean", "kotlin.Boolean", default = true),
        parameter("colors", "ButtonColors", BUTTON_COLORS, default = true),
        parameter(
          "label",
          "RowScope.() -> Unit",
          slot = true,
          receiver = "androidx.compose.foundation.layout.RowScope",
        ),
        parameter(
          "secondaryLabel",
          "RowScope.() -> Unit",
          default = true,
          slot = true,
          receiver = "androidx.compose.foundation.layout.RowScope",
        ),
      ),
    )

  private val records =
    ComponentRecordFile.Builder(
        "wear",
        "release",
        listOf(
          button("Button", listOf("wear-m3/button")),
          button("FilledTonalButton", emptyList()),
          record(
            "ListHeader",
            listOf("wear-m3/list-header"),
            listOf(
              parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
              parameter(
                "content",
                "RowScope.() -> Unit",
                slot = true,
                receiver = "androidx.compose.foundation.layout.RowScope",
              ),
            ),
          ),
          record(
            "Text",
            listOf("wear-m3/text"),
            listOf(
              parameter("text", "String", "kotlin.String"),
              parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            ),
          ),
        ),
      )
      .build()

  /** As a catalog publishes it, on `statusSemantics.components["wear-m3/button"]`. */
  private val buttonProjection =
    Json.parseToJsonElement(
      """
      { "variantProperty": "variant",
        "targets": [
          { "record": "wear/androidx.wear.compose.material3.ButtonKt.Button",
            "slots": { "content": "label" },
            "parameters": {
              "enabled": { "property": "enabled", "codec": "bool", "omitWhen": true },
              "colors": { "call": "androidx.wear.compose.material3.ButtonDefaults.buttonColors",
                          "type": "$BUTTON_COLORS",
                          "arguments": { "containerColor": { "property": "containerColor",
                            "codec": { "color": { "scheme": "androidx.wear.compose.material3.MaterialTheme" } } } } } } },
          { "record": "wear/androidx.wear.compose.material3.FilledTonalButtonKt.FilledTonalButton",
            "variants": ["filled-tonal"],
            "slots": { "content": "label" },
            "parameters": {
              "enabled": { "property": "enabled", "codec": "bool", "omitWhen": true },
              "colors": { "call": "androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors",
                          "type": "$BUTTON_COLORS",
                          "arguments": { "containerColor": { "property": "containerColor",
                            "codec": { "color": { "scheme": "androidx.wear.compose.material3.MaterialTheme" } } } } } } } ] }
      """
    )

  private val textProjection =
    Json.parseToJsonElement(
      """
      { "targets": [ { "record": "wear/androidx.wear.compose.material3.TextKt.Text",
          "parameters": { "text": { "property": "text", "codec": "text" } } } ] }
      """
    )

  /** The button again, recognising one or two lines as `label` and `secondaryLabel`. */
  private val patternedButtonProjection =
    Json.parseToJsonElement(
      """
      { "targets": [
          { "record": "wear/androidx.wear.compose.material3.ButtonKt.Button",
            "slotPatterns": { "content": {
              "patterns": [ { "match": [
                { "lines": { "text": "wear-m3/text", "min": 1, "max": 2 },
                  "to": ["label", "secondaryLabel"] } ] } ] } } } ] }
      """
    )

  /** `ListHeader`'s header is its `text` property, written as the catalog's own `Text`. */
  private val listHeaderProjection =
    Json.parseToJsonElement(
      """
      { "targets": [
          { "record": "wear/androidx.wear.compose.material3.ListHeaderKt.ListHeader",
            "synthesize": { "content": { "component": "wear-m3/text",
                                         "properties": { "text": "text" } } } } ] }
      """
    )

  private val projections: Map<String, ExportProjection> =
    mapOf(
      "wear-m3/button" to ExportProjectionJson.decode(buttonProjection).getOrThrow(),
      "wear-m3/text" to ExportProjectionJson.decode(textProjection).getOrThrow(),
    )

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/evidence/ui-builder-repetition-export").isDirectory }

  private fun document(
    button: DesignNodeV1,
    labelProperties: Map<String, UiValueV1> = emptyMap(),
  ): DesignDocumentV1 {
    val base =
      Json.decodeFromString<DesignDocumentV1>(
        File(root, "docs/design/evidence/ui-builder-repetition-export/repetition-initial.uid")
          .readText()
      )
    val label =
      DesignNodeV1(
        "label",
        "wear-m3/text",
        properties = mapOf("text" to StringValueV1("Go")) + labelProperties,
      )
    return base.copy(
      title = "Projected",
      stateVariables = emptyMap(),
      components = emptyMap(),
      roots = listOf(button.id),
      nodes = mapOf(button.id to button, label.id to label),
    )
  }

  private fun button(vararg properties: Pair<String, UiValueV1>) =
    DesignNodeV1(
      "go",
      "wear-m3/button",
      properties = properties.toMap(),
      slots = mapOf("content" to listOf("label")),
    )

  private fun emitted(document: DesignDocumentV1): String {
    val outcome = ScreenExportGate.export(document, records, projections)
    return assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
  }

  @Test
  fun `a button group's children share it out by weight, without the row's fill`() {
    val group =
      record(
        "ButtonGroup",
        listOf("wear-m3/button-group"),
        listOf(
          parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
          parameter(
            "content",
            "ButtonGroupScope.() -> Unit",
            slot = true,
            receiver = "androidx.wear.compose.material3.ButtonGroupScope",
          ),
        ),
      )
    val groupProjection =
      ExportProjectionJson.decode(
          Json.parseToJsonElement(
            """{ "targets": [ { "record": "${group.canonicalId}",
              "slots": { "children": "content" } } ] }"""
          )
        )
        .getOrThrow()
    fun export(fill: Boolean?, weight: Double = 0.7): ScreenExportGate.Outcome {
      val base = document(button())
      val weighted =
        base.nodes
          .getValue("go")
          .copy(modifiers = listOf(WeightModifierV1(JsonPrimitive(weight), fill)))
      val node =
        DesignNodeV1("group", "wear-m3/button-group", slots = mapOf("children" to listOf("go")))
      return ScreenExportGate.export(
        base.copy(
          roots = listOf("group"),
          nodes = base.nodes + ("go" to weighted) + ("group" to node),
        ),
        ComponentRecordFile.Builder("wear", "release", records.components + group).build(),
        projections + ("wear-m3/button-group" to groupProjection),
      )
    }
    val source =
      assertIs<ScreenExportGate.Outcome.Emitted>(export(null), export(null).toString()).source
    assertContains(source, "ButtonGroup {")
    assertContains(source, "Button(onClick = {}, modifier = Modifier.weight(0.7f), label = {")
    val refused = assertIs<ScreenExportGate.Outcome.Refused>(export(false))
    assertTrue(refused.reasons.any { "does not take" in it }, refused.reasons.toString())
    val zero = assertIs<ScreenExportGate.Outcome.Refused>(export(null, weight = 0.0))
    assertTrue(zero.reasons.any { "only a positive weight" in it }, zero.reasons.toString())
  }

  @Test
  fun `a variant exports as the real library composable with its factory colours`() {
    val source =
      emitted(
        document(
          button(
            "variant" to EnumValueV1("filled-tonal"),
            "enabled" to BooleanValueV1(false),
            "containerColor" to ColorTokenValueV1("primary"),
          )
        )
      )
    assertContains(source, "FilledTonalButton(")
    assertContains(source, "enabled = false")
    assertContains(
      source,
      "ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary)",
    )
    assertContains(source, "Text(text = \"Go\")")
    assertContains(source, "import androidx.wear.compose.material3.FilledTonalButton")
  }

  @Test
  fun `an unstyled default keeps the library's own colours and defaults`() {
    val source = emitted(document(button()))
    assertContains(source, "Button(")
    assertFalse("FilledTonalButton" in source, source)
    assertFalse("colors =" in source, source)
    assertFalse("enabled =" in source, source)
  }

  @Test
  fun `a property the projection does not write is refused rather than dropped`() {
    val outcome =
      ScreenExportGate.export(
        document(button("shape" to StringValueV1("pill"))),
        records,
        projections,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "`shape` is not written by" in it }, reasons.toString())
  }

  @Test
  fun `a malformed literal is refused with the parameter it was for`() {
    val outcome =
      ScreenExportGate.export(
        document(button("containerColor" to ColorTokenValueV1("not a role"))),
        records,
        projections,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "containerColor" in it && "node `go`" in it }, reasons.toString())
  }

  @Test
  fun `the allowlist widens only to owners in the catalog's own packages`() {
    val rogue =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "wear/androidx.wear.compose.material3.ButtonKt.Button",
            mapOf(
              "colors" to
                ExportProjection.Value.Call(
                  "java.lang.Runtime.getRuntime",
                  BUTTON_COLORS,
                  mapOf(
                    "x" to ExportProjection.Value.Property("enabled", ExportProjection.Codec.Bool)
                  ),
                )
            ),
          )
        )
      )
    val owners =
      ScreenExportGate.projectedOwners(
        projections + ("rogue" to rogue),
        records,
      )
    assertEquals(
      setOf(
        "androidx.wear.compose.material3.ButtonDefaults",
        "androidx.wear.compose.material3.MaterialTheme",
      ),
      owners,
    )
  }

  @Test
  fun `a top-level call in the catalog's own package is admitted, one elsewhere is not`() {
    fun topLevel(callable: String) =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "wear/androidx.wear.compose.material3.ButtonKt.Button",
            emptyMap(),
            contexts =
              mapOf(
                "list-item" to
                  ExportProjection.Context(
                    mapOf(
                      "transformation" to
                        ExportProjection.Value.Fixed(
                          ScreenValue.Construct(callable, typeFqn = "$callable.Type")
                        )
                    )
                  )
              ),
          )
        )
      )
    // `SurfaceTransformation(spec)` is top level in the package the catalog's buttons live in.
    val owners =
      ScreenExportGate.projectedOwners(
        mapOf(
          "own" to topLevel("androidx.wear.compose.material3.SurfaceTransformation"),
          "foreign" to topLevel("java.lang.exit"),
        ),
        records,
      )
    // The callable itself, never its whole package.
    assertTrue("androidx.wear.compose.material3.SurfaceTransformation" in owners, owners.toString())
    assertFalse("androidx.wear.compose.material3" in owners, owners.toString())
    assertFalse(owners.any { it.startsWith("java.") }, owners.toString())
  }

  @Test
  fun `a projected slot keeps the receiver its record declares`() {
    // `weight` is `RowScope.weight`: legal only because `label` is a `RowScope` slot.
    val source = emitted(document(button(), mapOf("weight" to DecimalValueV1(1.0))))
    assertContains(source, ".weight(")
  }

  @Test
  fun `projections are read from the published component policies`() {
    val semantics = buildJsonObject {
      putJsonObject("components") {
        putJsonObject("wear-m3/button") { put("exportProjection", buttonProjection) }
        putJsonObject("wear-m3/text") { put("exportProjection", JsonNull) }
        putJsonObject("wear-m3/broken") { put("exportProjection", JsonPrimitive("nonsense")) }
      }
    }
    assertEquals(
      mapOf("wear-m3/button" to projections.getValue("wear-m3/button")),
      CatalogExportProjections.from(semantics),
    )
  }

  @Test
  fun `a builtin exports through the projection of the record component implementing it`() {
    val listRecord =
      "wear/androidx.wear.compose.foundation.lazy.TransformingLazyColumnKt.TransformingLazyColumn"
    val semantics = buildJsonObject {
      putJsonObject("components") {
        putJsonObject("wear-m3/transforming-lazy-column-call") {
          put("record", listRecord)
          put("exportProjection", textProjection)
        }
      }
      putJsonObject("builtins") {
        putJsonObject("wear-m3/transforming-lazy-column") {
          put("role", "list")
          put("implementation", listRecord)
        }
        putJsonObject("wear-m3/screen-scaffold") { put("role", "screen-root") }
        putJsonObject("wear-m3/unimplemented") {
          put("role", "list")
          put("implementation", "wear/nothing.Nothing")
        }
      }
    }
    val projections = CatalogExportProjections.from(semantics)
    val projection = ExportProjectionJson.decode(textProjection).getOrThrow()
    assertEquals(
      mapOf(
        "wear-m3/transforming-lazy-column-call" to projection,
        "wear-m3/transforming-lazy-column" to projection,
      ),
      projections,
    )
  }

  @Test
  fun `a projected Material icon key is written from this build's icon table`() {
    val icon =
      record(
        "Icon",
        listOf("wear-m3/icon"),
        listOf(
          parameter(
            "imageVector",
            "ImageVector",
            "androidx.compose.ui.graphics.vector.ImageVector",
          ),
          parameter("contentDescription", "String?", "kotlin.String")
            .newBuilder()
            .also { it.nullable = true }
            .build(),
        ),
      )
    val projection =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            icon.canonicalId,
            mapOf(
              "imageVector" to
                ExportProjection.Value.Property("iconKey", ExportProjection.Codec.MaterialIcon)
            ),
          )
        )
      )
    fun exported(key: String): ScreenExportGate.Outcome {
      val base = document(button())
      val node =
        DesignNodeV1(
          "glyph",
          "wear-m3/icon",
          properties = mapOf("iconKey" to EnumValueV1(key)),
        )
      return ScreenExportGate.export(
        base.copy(roots = listOf("glyph"), nodes = mapOf("glyph" to node)),
        ComponentRecordFile.Builder("wear", "release", records.components + icon).build(),
        mapOf("wear-m3/icon" to projection),
      )
    }
    val source =
      assertIs<ScreenExportGate.Outcome.Emitted>(exported("filled/add"), "filled/add").source
    assertContains(source, "Icon(imageVector = Icons.Filled.Add")
    assertContains(source, "import androidx.compose.material.icons.filled.Add")
    val refused = assertIs<ScreenExportGate.Outcome.Refused>(exported("filled/noSuchIcon"))
    assertTrue(
      refused.reasons.any { "is not a Material icon key" in it },
      refused.reasons.toString(),
    )
  }

  @Test
  fun `a catalog without projections exports exactly as before`() {
    val document = document(button())
    assertEquals(
      ScreenExportGate.export(document, records),
      ScreenExportGate.export(document, records, emptyMap()),
    )
  }

  private fun patterned(): Map<String, ExportProjection> =
    projections +
      ("wear-m3/button" to ExportProjectionJson.decode(patternedButtonProjection).getOrThrow())

  /** A button whose content is [content], over [extra] nodes beside the `Go` label. */
  private fun withContent(content: List<String>, vararg extra: DesignNodeV1): DesignDocumentV1 {
    val base = document(button().copy(slots = mapOf("content" to content)))
    return base.copy(nodes = base.nodes + extra.associateBy { it.id })
  }

  private fun text(id: String, text: String) =
    DesignNodeV1(id, "wear-m3/text", properties = mapOf("text" to StringValueV1(text)))

  @Test
  fun `a column of two texts becomes the label and secondary label, and the column is not written`() {
    val column =
      DesignNodeV1(
        "lines",
        "layout/column",
        slots = mapOf("children" to listOf("label", "later")),
      )
    val outcome =
      ScreenExportGate.export(
        withContent(listOf("lines"), column, text("later", "Later")),
        records,
        patterned(),
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "label = {")
    assertContains(source, "secondaryLabel = {")
    assertContains(source, "Text(text = \"Later\")")
    assertFalse("Column" in source, source)
  }

  @Test
  fun `a single text is the label alone`() {
    val source =
      ScreenExportGate.export(document(button()), records, patterned()).let {
        assertIs<ScreenExportGate.Outcome.Emitted>(it, it.toString()).source
      }
    assertContains(source, "Text(text = \"Go\")")
    assertFalse("secondaryLabel" in source, source)
  }

  @Test
  fun `children no pattern matches are refused with the slot and what they were`() {
    val arranged =
      DesignNodeV1(
        "lines",
        "layout/column",
        properties = mapOf("verticalArrangement" to EnumValueV1("center")),
        slots = mapOf("children" to listOf("label")),
      )
    val outcome =
      ScreenExportGate.export(withContent(listOf("lines"), arranged), records, patterned())
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(
      reasons.any { "node `go`.`content`" in it && "[layout/column]" in it },
      reasons.toString(),
    )
  }

  @Test
  fun `a column whose arrangement is bound or unknown is not read as lines`() {
    for (arrangement in listOf<UiValueV1>(StateValueV1("arrangement"), EnumValueV1("sideways"))) {
      val column =
        DesignNodeV1(
          "lines",
          "layout/column",
          properties = mapOf("verticalArrangement" to arrangement),
          slots = mapOf("children" to listOf("label")),
        )
      val outcome =
        ScreenExportGate.export(withContent(listOf("lines"), column), records, patterned())
      val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
      assertTrue(reasons.any { "[layout/column]" in it }, "$arrangement: $reasons")
    }
  }

  @Test
  fun `a column stating its default arrangement and alignment is still read as lines`() {
    val column =
      DesignNodeV1(
        "lines",
        "layout/column",
        properties =
          mapOf(
            "verticalArrangement" to EnumValueV1("top"),
            "horizontalAlignment" to EnumValueV1("start"),
          ),
        slots = mapOf("children" to listOf("label")),
      )
    val outcome =
      ScreenExportGate.export(withContent(listOf("lines"), column), records, patterned())
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertFalse("Column" in source, source)
  }

  @Test
  fun `a property written as a child exports through that child's own projection`() {
    val header =
      DesignNodeV1(
        "header",
        "wear-m3/list-header",
        properties = mapOf("text" to StringValueV1("Settings")),
      )
    val base = document(button())
    val outcome =
      ScreenExportGate.export(
        base.copy(roots = listOf("header"), nodes = mapOf("header" to header)),
        records,
        projections +
          ("wear-m3/list-header" to ExportProjectionJson.decode(listHeaderProjection).getOrThrow()),
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "ListHeader {")
    assertContains(source, "Text(text = \"Settings\")")
  }

  @Test
  fun `a synthesized child of a component with no projection is refused by name`() {
    val header =
      DesignNodeV1(
        "header",
        "wear-m3/list-header",
        properties = mapOf("text" to StringValueV1("Settings")),
      )
    val outcome =
      ScreenExportGate.export(
        document(button()).copy(roots = listOf("header"), nodes = mapOf("header" to header)),
        records,
        mapOf(
          "wear-m3/list-header" to ExportProjectionJson.decode(listHeaderProjection).getOrThrow()
        ),
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(
      reasons.any { "`content` is written as a `wear-m3/text`" in it },
      reasons.toString(),
    )
  }

  private val itemScope = "androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope"
  private val spec = "androidx.wear.compose.material3.lazy.TransformationSpec"
  private val listState = "androidx.wear.compose.foundation.lazy.TransformingLazyColumnState"

  private val transformingList =
    ComponentRecord.Builder(
        canonicalId =
          "wear/androidx.wear.compose.foundation.lazy.TransformingLazyColumnKt.TransformingLazyColumn",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.foundation.lazy.TransformingLazyColumnKt",
              callable = "androidx.wear.compose.foundation.lazy.TransformingLazyColumn",
              name = "TransformingLazyColumn",
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.componentIds = listOf("wear-m3/list")
        b.signatureKnown = true
        b.parameters =
          listOf(
            parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            parameter("state", "TransformingLazyColumnState", listState, default = true),
            TargetParameter.Builder(
                name = "content",
                type = "TransformingLazyColumnScope.() -> Unit",
              )
              .also {
                it.scopeDslReceiver =
                  "androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope"
                it.lambdaReturnTypeFqn = "kotlin.Unit"
              }
              .build(),
          )
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "TransformingLazyColumn {}"
              c.imports = listOf("androidx.wear.compose.foundation.lazy.TransformingLazyColumn")
            }
            .build()
      }
      .build()

  private val listRecords =
    ComponentRecordFile.Builder("wear", "release", records.components + transformingList).build()

  /** The list writes its state from the frame's local and wraps each child in `item`. */
  private val listProjection =
    ExportProjection(
      listOf(
        ExportProjection.Target(
          transformingList.canonicalId,
          mapOf(
            "state" to
              ExportProjection.Value.Fixed(
                ScreenValue.LocalRead.Builder("listState", listState).build()
              )
          ),
          items = mapOf("content" to "item"),
          itemRoles = mapOf("content" to "list-item"),
        )
      )
    )

  /** The button, adding `transformedHeight(this, spec)` when it is a list item. */
  private val itemButtonProjection =
    ExportProjectionJson.decode(buttonProjection).getOrThrow().let { projection ->
      projection.copy(
        targets =
          projection.targets.map { target ->
            target.copy(
              contexts =
                mapOf(
                  "list-item" to
                    ExportProjection.Context(
                      modifier =
                        listOf(
                          ChainLink(
                            "androidx.wear.compose.material3.lazy.transformedHeight",
                            positional =
                              listOf(
                                ScreenValue.ScopeRead.Builder(itemScope).build(),
                                ScreenValue.LocalRead.Builder("spec", spec).build(),
                              ),
                          )
                        )
                    )
                )
            )
          }
      )
    }

  private val wearStructure =
    CatalogScreenStructure.from(
      Json.parseToJsonElement(
          """
          { "code": {
              "imports": [
                "androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState",
                "androidx.wear.compose.material3.ScreenScaffold",
                "androidx.wear.compose.material3.lazy.rememberTransformationSpec" ],
              "templates": { "screen-root":
                "val listState = rememberTransformingLazyColumnState()\nval spec = rememberTransformationSpec()\nScreenScaffold(scrollState = listState) { contentPadding ->\n  ${'$'}{content}\n}" },
              "locals": { "listState": "$listState", "spec": "$spec" },
              "scopes": { "list-item": "$itemScope" } },
            "builtins": { "wear-m3/screen-scaffold": { "role": "screen-root" } } }
          """
        )
        .jsonObject
    )

  private fun listDocument(): DesignDocumentV1 {
    val base = document(button())
    val list = DesignNodeV1("list", "wear-m3/list", slots = mapOf("content" to listOf("go")))
    return base.copy(roots = listOf("list"), nodes = base.nodes + ("list" to list))
  }

  @Test
  fun `a projected list writes the frame around it, each child in an item with its role's links`() {
    val outcome =
      ScreenExportGate.export(
        listDocument(),
        listRecords,
        projections +
          mapOf("wear-m3/list" to listProjection, "wear-m3/button" to itemButtonProjection),
        wearStructure,
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "val listState = rememberTransformingLazyColumnState()")
    assertContains(source, "ScreenScaffold(scrollState = listState) { contentPadding ->")
    assertContains(source, "TransformingLazyColumn(state = listState) {")
    assertContains(source, "item {")
    assertContains(source, "Modifier.transformedHeight(this, spec)")
    assertContains(source, "import androidx.wear.compose.material3.ScreenScaffold")
  }

  @Test
  fun `a list whose item member opens no role wraps its rows without the role's links`() {
    val plainList =
      listProjection.copy(targets = listProjection.targets.map { it.copy(itemRoles = emptyMap()) })
    val outcome =
      ScreenExportGate.export(
        listDocument(),
        listRecords,
        projections + mapOf("wear-m3/list" to plainList, "wear-m3/button" to itemButtonProjection),
        wearStructure,
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "item {")
    assertFalse("transformedHeight" in source, source)
  }

  @Test
  fun `a button outside a list writes no list-item links`() {
    val outcome =
      ScreenExportGate.export(
        document(button()),
        listRecords,
        projections + ("wear-m3/button" to itemButtonProjection),
        wearStructure,
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertFalse("transformedHeight" in source, source)
  }

  @Test
  fun `an item scope the catalog does not attest leaves the scope read refused`() {
    val outcome =
      ScreenExportGate.export(
        listDocument(),
        listRecords,
        projections +
          mapOf("wear-m3/list" to listProjection, "wear-m3/button" to itemButtonProjection),
        wearStructure.copy(scopes = emptyMap()),
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { itemScope in it }, reasons.toString())
  }

  private fun scaffolded(
    vararg properties: Pair<String, UiValueV1>,
    slots: Map<String, List<String>> = mapOf("content" to listOf("list")),
  ): DesignDocumentV1 {
    val base = listDocument()
    val scaffold =
      DesignNodeV1(
        "screen",
        "wear-m3/screen-scaffold",
        properties = properties.toMap(),
        slots = slots,
      )
    return base.copy(roots = listOf("screen"), nodes = base.nodes + ("screen" to scaffold))
  }

  private fun exportList(document: DesignDocumentV1) =
    ScreenExportGate.export(
      document,
      listRecords,
      projections +
        mapOf("wear-m3/list" to listProjection, "wear-m3/button" to itemButtonProjection),
      wearStructure,
    )

  @Test
  fun `the catalog's screen-root builtin is read from its builtins`() {
    assertEquals("wear-m3/screen-scaffold", wearStructure.rootComponent)
  }

  @Test
  fun `a design rooted on the screen-root builtin exports its content inside the frame`() {
    val outcome =
      exportList(
        scaffolded(
          "timeText" to StringValueV1("10:10"),
          "scrollIndicator" to BooleanValueV1(true),
        )
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertEquals(
      source,
      assertIs<ScreenExportGate.Outcome.Emitted>(exportList(listDocument())).source,
    )
    assertContains(source, "ScreenScaffold(scrollState = listState) { contentPadding ->")
    assertFalse("screen-scaffold" in source, source)
  }

  @Test
  fun `what the screen-root frame cannot write is refused by name`() {
    val reasons =
      assertIs<ScreenExportGate.Outcome.Refused>(
          exportList(
            scaffolded(
              "scrollIndicator" to BooleanValueV1(false),
              "background" to StringValueV1("#000000"),
              slots = mapOf("content" to listOf("list"), "edgeButton" to listOf("go")),
            )
          )
        )
        .reasons
    assertTrue(reasons.any { "`scrollIndicator`" in it }, reasons.toString())
    assertTrue(reasons.any { "`background`" in it }, reasons.toString())
    assertTrue(reasons.any { "`edgeButton` slot" in it }, reasons.toString())
    val empty =
      assertIs<ScreenExportGate.Outcome.Refused>(exportList(scaffolded(slots = emptyMap())))
    assertTrue(empty.reasons.any { "no content" in it }, empty.reasons.toString())
  }

  @Test
  fun `a filled root slot exports into the frame the catalog publishes for it`() {
    val edge =
      DesignNodeV1("edge", "wear-m3/text", properties = mapOf("text" to StringValueV1("Go")))
    val document =
      scaffolded(slots = mapOf("content" to listOf("list"), "edgeButton" to listOf("edge"))).let {
        it.copy(nodes = it.nodes + ("edge" to edge))
      }
    val structure =
      wearStructure.copy(
        slotFrames =
          mapOf(
            setOf("edgeButton") to
              "val listState = rememberTransformingLazyColumnState()\n" +
                "val spec = rememberTransformationSpec()\n" +
                "ScreenScaffold(scrollState = listState, edgeButton = {\n" +
                "  ${'$'}{edgeButton}\n" +
                "}) { contentPadding ->\n" +
                "  ${'$'}{content}\n" +
                "}"
          )
      )
    val outcome =
      ScreenExportGate.export(
        document,
        listRecords,
        projections +
          mapOf("wear-m3/list" to listProjection, "wear-m3/button" to itemButtonProjection),
        structure,
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "ScreenScaffold(scrollState = listState, edgeButton = {\n")
    assertContains(source, "Text(text = \"Go\")\n    }) { contentPadding ->")
    assertContains(source, "TransformingLazyColumn(state = listState) {")
    // The plain frame stays for a screen that fills no other slot.
    assertIs<ScreenExportGate.Outcome.Emitted>(
      ScreenExportGate.export(
        scaffolded(),
        listRecords,
        projections +
          mapOf("wear-m3/list" to listProjection, "wear-m3/button" to itemButtonProjection),
        structure,
      )
    )
  }

  @Test
  fun `a frame per filled slot set is read from its template key`() {
    val structure =
      CatalogScreenStructure.from(
        Json.parseToJsonElement(
            """{ "code": { "templates": { "screen-root": "a", "screen-root+edgeButton": "b",
              "screen-root+edgeButton+overlays": "c" } } }"""
          )
          .jsonObject
      )
    assertEquals("a", structure.frameFor(emptySet()))
    assertEquals("b", structure.frameFor(setOf("edgeButton")))
    assertEquals("c", structure.frameFor(setOf("overlays", "edgeButton")))
    assertEquals(null, structure.frameFor(setOf("overlays")))
  }

  @Test
  fun `without a frame the screen-root builtin is left to the record path`() {
    val document = scaffolded()
    assertIs<ScreenRootFrame.Unwrapped.Document>(
        ScreenRootFrame.unwrap(document, wearStructure.copy(frame = null))
      )
      .let { assertEquals(document, it.document) }
  }

  private companion object {
    const val BUTTON_COLORS = "androidx.wear.compose.material3.ButtonColors"
  }
}
