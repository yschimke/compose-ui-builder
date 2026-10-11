package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WearScreenCodeExporterTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  /**
   * The canvas draws a plain Column in a stadium; the generated source must be the real
   * `ScreenScaffold` around a `TransformingLazyColumn`, with `transformedHeight` and
   * `SurfaceTransformation` on every item.
   */
  @Test
  fun `the screen generates a real ScreenScaffold over a TransformingLazyColumn`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    write("ActivityScreen.kt", source)
    assertTrue("ScreenScaffold(scrollState = listState" in source, source)
    assertTrue("TransformingLazyColumn(" in source, source)
    assertTrue("contentPadding = contentPadding," in source, source)
    assertTrue(
      "minimumVerticalContentPadding(CardDefaults.minimumVerticalListContentPadding)" in source,
      source,
    )
    assertTrue("Modifier.transformedHeight(this, spec)" in source, source)
    assertTrue("transformation = SurfaceTransformation(spec)" in source, source)
    assertTrue("val spec = rememberTransformationSpec()" in source, source)
    // The stand-in is emitted, not erased — the opposite of the widget container.
    assertTrue("screen-scaffold" !in source, source)
    assertTrue("transforming-lazy-column" !in source, source)
  }

  /**
   * The status strip is `AppScaffold`'s, which belongs to the app root: previews wrap the screen in
   * it (frozen at the design's time), and the screen itself is a droppable `ScreenScaffold`.
   */
  @Test
  fun `a declared timeText is the previews' AppScaffold, not the screen's`() {
    val emitted =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
        WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
      )
    val source = emitted.source
    val appScaffold =
      "AppScaffold(timeText = { TimeText { timeTextCurvedText(\"10:10\") } }) { ActivityScreen() }"

    assertEquals("ActivityScreen", emitted.screenName)
    val screen = source.substringAfter("fun ActivityScreen() {").substringBefore("\n}\n")
    assertFalse("AppScaffold" in screen, screen)
    assertFalse("TimeText" in screen, screen)
    assertTrue("fun ActivityScreenPreview() {\n    $appScaffold\n}" in source, source)
    assertTrue("fun ActivityScreenLongPreview() {\n    $appScaffold\n}" in source, source)
  }

  /**
   * The native lane renders without the export's previews, so it gets a wrapper putting the screen
   * in its `AppScaffold`.
   */
  @Test
  fun `the native lane is given the screen inside its AppScaffold`() {
    val emitted =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
        WearScreenCodeExporter.export(
          wearScreenUiBuilderDocument("activity", pin, environment),
          tagNodes = true,
        )
      )

    // The name the lane imports is the design's in both modes; there it is the wrapper.
    assertEquals("ActivityScreen", emitted.screenName)
    assertTrue("fun ActivityScreenContent() {" in emitted.source, emitted.source)
    assertTrue(
      "fun ActivityScreen() {\n" +
        "    AppScaffold(timeText = { TimeText { timeTextCurvedText(\"10:10\") } }) { ActivityScreenContent() }\n" +
        "}" in emitted.source,
      emitted.source,
    )
  }

  /** Every round size, because where a Wear list wraps is the question a single render dodges. */
  @Test
  fun `the generated preview fans out across the round devices`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    assertTrue("@WearPreviewDevices" in source, source)
    assertTrue("fun ActivityScreenPreview() {" in source, source)
  }

  /**
   * The native preview lane compiles against a catalog runtime bundle without `compose-ui-tooling`
   * or `preview-annotations`, and wraps the composable in its own `@Preview`, so the preview
   * fan-out must be omitted there.
   */
  @Test
  fun `the native lane's source carries no preview fan-out, and the artifact's does`() {
    val document = wearScreenUiBuilderDocument("activity", pin, environment)
    // `tagNodes = true` alone must suffice, so the server needn't pass `previews = false` (a
    // cross-repo API change).
    val native =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(
            document,
            packageName = "generated.uibuilder",
            tagNodes = true,
          )
        )
        .source
    val artifact =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(document, packageName = "generated.uibuilder")
        )
        .source

    assertFalse("@WearPreviewDevices" in native, native)
    assertFalse("@ScrollingPreview" in native, native)
    assertFalse("androidx.wear.compose.ui.tooling.preview.WearPreviewDevices" in native, native)
    assertFalse("ee.schimke.composeai.preview.ScrollingPreview" in native, native)
    // The export artifact keeps them, and keeps compiling with them: a designer's file renders in
    // an IDE where those two artifacts are on the classpath by definition.
    assertTrue("@WearPreviewDevices" in artifact, artifact)
    assertTrue("@ScrollingPreview" in artifact, artifact)
  }

  /** A widget is the other generator's job, and saying so beats emitting something plausible. */
  @Test
  fun `a design that is not a wear screen is refused by name`() {
    val widget = helloWidgetUiBuilderDocument("hello", pin, environment)

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(widget))

    assertEquals(1, refused.reasons.size)
    assertTrue(
      "remote-m3/widget-container-small" in refused.reasons.single(),
      refused.reasons.single(),
    )
  }

  /**
   * A borrowed `m3-catalog` component with no Wear counterpart is refused by name rather than
   * approximated with uncompilable Kotlin.
   */
  @Test
  fun `a borrowed component with no Wear counterpart is refused by node`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-list" to list.copy(slots = mapOf("items" to listOf("chip")))) +
            ("chip" to UiBuilderNode(id = "chip", componentId = "m3/filter-chip"))
      )

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(document))

    assertTrue("m3/filter-chip" in refused.reasons.single(), refused.reasons.single())
    assertTrue("chip" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * Authored text is escaped for the literal it lands in (e.g. the two-line ComposeStarter
   * greeting).
   */
  @Test
  fun `a label with a line break or a dollar sign is written as an escaped literal`() {
    val document =
      withListItems(
        UiBuilderNode(
          id = "greeting",
          componentId = "wear-m3/list-header",
          properties =
            JsonObject(mapOf("text" to text("From the Round world,\nHello, \$5 \"Android\"!"))),
        )
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document))
        .source

    assertTrue(
      "Text(text = \"From the Round world,\\nHello, \\\$5 \\\"Android\\\"!\")" in source,
      source,
    )
  }

  /**
   * Two ids that fold to one Kotlin name (`volume-level`, `volume_level`) get distinct state, not a
   * redeclaration.
   */
  @Test
  fun `node ids that fold to the same state name get distinct locals`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    fun slider(id: String) =
      UiBuilderNode(
        id = id,
        componentId = "wear-m3/slider",
        properties = JsonObject(mapOf("value" to number(3f))),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-list" to
              list.copy(slots = mapOf("items" to listOf("volume-level", "volume_level")))) +
            ("volume-level" to slider("volume-level")) +
            ("volume_level" to slider("volume_level"))
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document))
        .source

    assertTrue("var volumeLevelValue by remember" in source, source)
    assertTrue("var volumeLevelValue2 by remember" in source, source)
    assertTrue("value = volumeLevelValue2," in source, source)
  }

  /**
   * A button's lines go into its slots only when their column has no modifiers of its own;
   * otherwise the content form keeps them.
   */
  @Test
  fun `a button's column with modifiers is not collapsed into slots`() {
    fun line(id: String, value: String) =
      UiBuilderNode(
        id = id,
        componentId = WearScreenCodeExporter.TEXT,
        properties = JsonObject(mapOf("text" to text(value))),
      )
    val document =
      withListItems(
        UiBuilderNode(
          id = "row",
          componentId = WearScreenCodeExporter.BUTTON,
          slots = mapOf("content" to listOf("lines")),
        ),
        UiBuilderNode(
          id = "lines",
          componentId = "layout/column",
          modifiers =
            kotlinx.serialization.json.JsonArray(
              listOf(
                JsonObject(mapOf("type" to JsonPrimitive("padding"), "startDp" to JsonPrimitive(8)))
              )
            ),
          slots = mapOf("children" to listOf("title", "detail")),
        ),
        line("title", "Episode 140"),
        line("detail", "Jun 2, 2020"),
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document))
        .source

    assertFalse("secondaryLabel" in source, source)
    assertTrue("Column(modifier = Modifier.padding(start = 8.dp" in source, source)
  }

  /** The Code pane routes a Wear screen here rather than to the Compose gate's record refusal. */
  @Test
  fun `the editor's code pane generates the screen, not a compose refusal`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)

    val generated = reducer.generatedCode(wearScreenUiBuilderDocument("activity", pin, environment))

    val source = assertIs<EditorGeneratedCode.Source>(generated).kotlin
    assertTrue("TransformingLazyColumn(" in source, source)
  }

  /**
   * A **pack** node (another catalog's composable admitted into `wear-m3`) is written from its
   * record rather than refused. The fixture is Confetti's Wear `SectionHeader`: in a list it gets
   * the design's text, the row treatment on its modifier and transformation (as `ListHeader` does),
   * and its import from the record.
   */
  @Test
  fun `a pack component in the list is written from its record`() {
    val document =
      withListItems(
        UiBuilderNode(
          id = "day",
          componentId = "confetti-wear/section-header",
          properties = JsonObject(mapOf("text" to text("Thursday"))),
        )
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(document, packComponents = confettiWear())
        )
        .source

    assertTrue("import dev.johnoreilly.confetti.wear.components.SectionHeader" in source, source)
    assertTrue(
      "SectionHeader(\n" +
        "                    text = \"Thursday\",\n" +
        "                    modifier = Modifier.transformedHeight(this, spec),\n" +
        "                    transformation = SurfaceTransformation(spec),\n" +
        "                )" in source,
      source,
    )
    // The pack id is a design-side name; nothing of it reaches the Kotlin.
    assertFalse("section-header" in source, source)
    assertFalse("confetti-wear" in source, source)
  }

  /**
   * An unset required parameter takes the placeholder from the record's proven call site
   * (`code.call`); an unset defaulted one is omitted; a slot holds the node's children as Wear
   * content.
   */
  @Test
  fun `a pack container takes its placeholders from the proven call site and its children in its slot`() {
    val document =
      withListItems(
        UiBuilderNode(
          id = "group",
          componentId = "confetti-wear/session-group",
          properties = JsonObject(mapOf("expanded" to bool(true), "count" to number(3f))),
          slots = mapOf("content" to listOf("group-title")),
        ),
        UiBuilderNode(
          id = "group-title",
          componentId = WearScreenCodeExporter.TEXT,
          properties = JsonObject(mapOf("text" to text("Talks"))),
        ),
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(document, packComponents = confettiWear())
        )
        .source

    assertTrue(
      "SessionGroup(\n" +
        "                    onClick = {},\n" +
        "                    expanded = true,\n" +
        "                    count = 3,\n" +
        "                    content = {\n" +
        "                        Text(text = \"Talks\")\n" +
        "                    },\n" +
        "                )" in source,
      source,
    )
    // `SessionGroup` declares no `Modifier`, so the row treatment has nowhere to go and is not
    // invented; `label` is defaulted and unset, so it is omitted rather than filled.
    assertFalse("label =" in source, source)
    assertTrue("import dev.johnoreilly.confetti.wear.components.SessionGroup" in source, source)
  }

  /**
   * Tagging reaches a pack node through its `Modifier`, which is how the native overlay finds it.
   */
  @Test
  fun `a tagged pack node carries its id on its modifier`() {
    val document =
      withListItems(
        UiBuilderNode(
          id = "day",
          componentId = "confetti-wear/section-header",
          properties = JsonObject(mapOf("text" to text("Thursday"))),
        )
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(document, tagNodes = true, packComponents = confettiWear())
        )
        .source

    assertTrue(
      "modifier = Modifier.testTag(\"day\").transformedHeight(this, spec)," in source,
      source,
    )
  }

  /**
   * A required parameter with neither a design value nor a placeholder is refused by node and name.
   */
  @Test
  fun `a pack node missing a required value with no placeholder is refused by name`() {
    val document = withListItems(UiBuilderNode(id = "avatar", componentId = "confetti-wear/avatar"))

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(
        WearScreenCodeExporter.export(document, packComponents = confettiWear())
      )

    val reason = refused.reasons.single()
    assertTrue("confetti-wear/avatar" in reason, reason)
    assertTrue("`avatar`" in reason, reason)
    assertTrue("`name: String`" in reason, reason)
  }

  /** With no packs declared, a pack node is exactly the stranger it was before packs existed. */
  @Test
  fun `a pack node is refused when the screen was given no packs`() {
    val document =
      withListItems(UiBuilderNode(id = "day", componentId = "confetti-wear/section-header"))

    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(document))

    assertTrue("confetti-wear/section-header" in refused.reasons.single(), refused.reasons.single())
  }

  /** The template with its rows replaced by [items], the first of which is the list's only item. */
  private fun withListItems(vararg items: UiBuilderNode): UiBuilderDocument {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    return base.copy(
      nodes =
        base.nodes +
          ("wear-list" to list.copy(slots = mapOf("items" to listOf(items.first().id)))) +
          items.associateBy { it.id }
    )
  }

  private fun text(value: String) = property("string", JsonPrimitive(value))

  private fun bool(value: Boolean) = property("bool", JsonPrimitive(value))

  private fun number(value: Float) = property("float", JsonPrimitive(value))

  private fun property(type: String, value: JsonPrimitive) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  /**
   * Three Confetti Wear components as a pack's aliased record describes them: `SectionHeader` as it
   * really is, plus two invented shapes (a container with a required callback, a leaf whose only
   * parameter has no placeholder) so each rule has a case.
   */
  private fun confettiWear(): Map<String, ComponentRecord> =
    listOf(
        record(
          "SectionHeader",
          id = "confetti-wear/section-header",
          parameters =
            listOf(
              TargetParameter.Builder(name = "text", type = "String")
                .also { b -> b.typeFqn = "kotlin.String" }
                .build(),
              TargetParameter.Builder(name = "modifier", type = "Modifier")
                .also { b ->
                  b.typeFqn = WearScreenCodeExporter.MODIFIER_FQN
                  b.hasDefault = true
                }
                .build(),
              TargetParameter.Builder(name = "transformation", type = "SurfaceTransformation?")
                .also { b ->
                  b.typeFqn = WearScreenCodeExporter.SURFACE_TRANSFORMATION_FQN
                  b.hasDefault = true
                  b.nullable = true
                }
                .build(),
            ),
          call = "SectionHeader(text = \"\")",
        ),
        record(
          "SessionGroup",
          id = "confetti-wear/session-group",
          parameters =
            listOf(
              TargetParameter.Builder(name = "onClick", type = "() -> Unit").build(),
              TargetParameter.Builder(name = "label", type = "String")
                .also { b ->
                  b.typeFqn = "kotlin.String"
                  b.hasDefault = true
                }
                .build(),
              TargetParameter.Builder(name = "expanded", type = "Boolean")
                .also { b -> b.typeFqn = "kotlin.Boolean" }
                .build(),
              TargetParameter.Builder(name = "count", type = "Int")
                .also { b ->
                  b.typeFqn = "kotlin.Int"
                  b.hasDefault = true
                }
                .build(),
              TargetParameter.Builder(name = "content", type = "@Composable () -> Unit")
                .also { b -> b.composableSlot = true }
                .build(),
            ),
          slots = listOf(ComponentSlot.Builder(name = "content", required = true).build()),
          call = "SessionGroup(onClick = {}, expanded = false, content = {})",
        ),
        record(
          "AvatarPlaceholder",
          id = "confetti-wear/avatar",
          parameters =
            listOf(
              TargetParameter.Builder(name = "name", type = "String")
                .also { b -> b.typeFqn = "kotlin.String" }
                .build()
            ),
          // A record whose call site was proven by a producer that spelled no placeholder for
          // `name` — the shape a hand-edited or older record can have.
          call = "AvatarPlaceholder()",
        ),
      )
      .associateBy { it.componentIds.single() }

  private fun record(
    name: String,
    id: String,
    parameters: List<TargetParameter>,
    slots: List<ComponentSlot> = emptyList(),
    call: String,
  ): ComponentRecord {
    val callable = "dev.johnoreilly.confetti.wear.components.$name"
    return ComponentRecord.Builder(
        canonicalId = "confetti-wear/${callable}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "dev.johnoreilly.confetti.wear.components.${name}Kt",
              callable = callable,
              name = name,
              origin = ComponentOrigin.PROJECT,
            )
            .build(),
      )
      .also { builder_ ->
        builder_.componentIds = listOf(id)
        builder_.parameters = parameters
        builder_.slots = slots
        builder_.code =
          ComponentCode.Builder()
            .also { b ->
              b.call = call
              b.imports = listOf(callable)
            }
            .build()
        builder_.signatureKnown = true
      }
      .build()
  }

  /**
   * Three properties the generated screen reads and the canvas cannot: `scrollIndicator` (design
   * chrome), `stableKey` (item identity, only in `item(key = …)`), and the label's
   * `maxLines`/`overflow` (inside `ListHeader`'s content lambda).
   */
  @Test
  fun `the properties only the generated screen can honour are written`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-screen" to
              base.nodes
                .getValue("wear-screen")
                .copy(
                  properties =
                    JsonObject(
                      base.nodes.getValue("wear-screen").properties +
                        ("scrollIndicator" to property("boolean", JsonPrimitive(false)))
                    )
                )) +
            ("row-0" to
              base.nodes
                .getValue("row-0")
                .copy(
                  properties =
                    JsonObject(
                      base.nodes.getValue("row-0").properties +
                        ("stableKey" to property("string", JsonPrimitive("session-1")))
                    )
                )) +
            ("list-header" to
              base.nodes
                .getValue("list-header")
                .copy(
                  properties =
                    JsonObject(
                      base.nodes.getValue("list-header").properties +
                        ("maxLines" to property("number", JsonPrimitive(1))) +
                        ("overflow" to property("string", JsonPrimitive("ellipsis")))
                    )
                ))
      )

    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document))
        .source

    write("WearPropertyScreen.kt", source)
    assertTrue("scrollIndicator = null," in source, source)
    assertTrue("ScrollIndicator(listState)" !in source, source)
    assertTrue("item(key = \"session-1\") {" in source, source)
    assertTrue("maxLines = 1," in source, source)
    assertTrue("overflow = TextOverflow.Ellipsis," in source, source)
    assertTrue("import androidx.compose.ui.text.style.TextOverflow" in source, source)
  }

  /** A design that says nothing keeps upstream's own answers. */
  @Test
  fun `an undeclared property leaves the generated screen at its default`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    assertTrue("scrollIndicator = { if (!LocalScrollCaptureInProgress.current)" in source, source)
    assertTrue("item(key = " !in source, source)
    assertTrue("TextOverflow" !in source, source)
  }

  /**
   * A disabled button generates a disabled button, including the edge button, which comes from a
   * different branch (a `wear-m3/button` in the scaffold's slot).
   */
  @Test
  fun `a disabled Wear button is written disabled`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment, edgeButtonLabel = "Start")
    val disabledEdge =
      base.copy(
        nodes =
          base.nodes +
            ("edge-button" to
              base.nodes
                .getValue("edge-button")
                .copy(
                  properties =
                    JsonObject(
                      base.nodes.getValue("edge-button").properties +
                        ("enabled" to property("boolean", JsonPrimitive(false)))
                    )
                ))
      )
    val disabledButton =
      base.copy(
        nodes =
          base.nodes +
            ("row-action" to
              UiBuilderNode(
                id = "row-action",
                componentId = "wear-m3/button",
                properties =
                  JsonObject(mapOf("enabled" to property("boolean", JsonPrimitive(false)))),
                slots = mapOf("content" to listOf("row-action-label")),
              )) +
            ("row-action-label" to
              UiBuilderNode(
                id = "row-action-label",
                componentId = "wear-m3/text",
                properties = JsonObject(mapOf("text" to text("Start"))),
              )) +
            ("wear-list" to
              base.nodes
                .getValue("wear-list")
                .copy(
                  slots =
                    base.nodes.getValue("wear-list").slots +
                      ("items" to
                        (base.nodes.getValue("wear-list").slots.getValue("items") + "row-action"))
                ))
      )

    val edgeSource =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(disabledEdge))
        .source
    write("DisabledEdgeButtonScreen.kt", edgeSource)
    assertTrue("enabled = false" in edgeSource, edgeSource)

    val buttonSource =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(disabledButton))
        .source
    write("DisabledButtonScreen.kt", buttonSource)
    assertTrue("enabled = false," in buttonSource, buttonSource)

    // And a design that says nothing keeps the short form, so a default call is still a default
    // call rather than a wall of arguments.
    val enabled =
      assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(base)).source
    assertTrue("enabled = false" !in enabled, enabled)
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-wear-screen-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }
}
