package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
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
   * The whole point of the stand-in, in one assertion set.
   *
   * The canvas draws a stadium with a plain Column in it. What has to come out the other end is the
   * real thing: `ScreenScaffold` around a `TransformingLazyColumn`, with the row transformation on
   * every item. If the generated source ever stops carrying `transformedHeight` and
   * `SurfaceTransformation`, the fake container has stopped being a stand-in and become a lie.
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
    assertTrue("Modifier.transformedHeight(this, spec)" in source, source)
    assertTrue("transformation = SurfaceTransformation(spec)" in source, source)
    assertTrue("val spec = rememberTransformationSpec()" in source, source)
    // The stand-in is emitted, not erased — the opposite of the widget container.
    assertTrue("screen-scaffold" !in source, source)
    assertTrue("transforming-lazy-column" !in source, source)
  }

  /** The status strip is `AppScaffold`'s, not `ScreenScaffold`'s, and it is frozen. */
  @Test
  fun `a declared timeText generates the AppScaffold that owns it`() {
    val source =
      assertIs<WearScreenCodeExporter.Result.Emitted>(
          WearScreenCodeExporter.export(wearScreenUiBuilderDocument("activity", pin, environment))
        )
        .source

    assertTrue(
      "AppScaffold(timeText = { TimeText { timeTextCurvedText(\"10:10\") } })" in source,
      source,
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
    assertTrue("fun ActivityScreenPreview() = ActivityScreen()" in source, source)
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
   * A borrowed component with no Wear counterpart is named, not approximated.
   *
   * `wear-m3` lends its content components from `m3-catalog` while it has none of its own, and the
   * canvas will happily draw all of them. Only some map to Wear Compose Material 3, and a generator
   * that guessed at the rest would emit Kotlin that does not compile — which is worse than a
   * refusal that says which node to replace.
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
   * A **pack** node — another catalog's composable admitted into `wear-m3` — is written from its
   * record rather than refused as a stranger.
   *
   * The fixture is Confetti's Wear `SectionHeader` as its record describes it: a `String` text, a
   * defaulted `Modifier`, a defaulted `SurfaceTransformation`. Dropped into the list as an item it
   * takes the design's text, the row treatment on both the modifier and the transformation argument
   * — the same two `ListHeader` takes — and its callable is imported from the record.
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
        "                        text = \"Thursday\",\n" +
        "                        modifier = Modifier.transformedHeight(this, spec),\n" +
        "                        transformation = SurfaceTransformation(spec),\n" +
        "                    )" in source,
      source,
    )
    // The pack id is a design-side name; nothing of it reaches the Kotlin.
    assertFalse("section-header" in source, source)
    assertFalse("confetti-wear" in source, source)
  }

  /**
   * A required parameter the design does not set takes the placeholder the record's proven call
   * site used for it, read back out of `code.call` by name; a defaulted one it does not set is
   * omitted; and a slot holds the node's children, emitted as Wear content.
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
        "                        onClick = {},\n" +
        "                        expanded = true,\n" +
        "                        count = 3,\n" +
        "                        content = {\n" +
        "                            Text(text = \"Talks\")\n" +
        "                        },\n" +
        "                    )" in source,
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
   * A required parameter with neither a design value nor a placeholder refuses by node and name,
   * rather than inventing a value that the compile would then reject in the pack's vocabulary.
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
   * Three Confetti Wear components as a pack's aliased record describes them — `SectionHeader` as
   * its real signature reads, and two shapes it does not have (a container with a required
   * callback, a leaf whose only parameter has no placeholder) so each rule has a case.
   */
  private fun confettiWear(): Map<String, ComponentRecord> =
    listOf(
        record(
          "SectionHeader",
          id = "confetti-wear/section-header",
          parameters =
            listOf(
              TargetParameter(name = "text", type = "String", typeFqn = "kotlin.String"),
              TargetParameter(
                name = "modifier",
                type = "Modifier",
                typeFqn = WearScreenCodeExporter.MODIFIER_FQN,
                hasDefault = true,
              ),
              TargetParameter(
                name = "transformation",
                type = "SurfaceTransformation?",
                typeFqn = WearScreenCodeExporter.SURFACE_TRANSFORMATION_FQN,
                hasDefault = true,
                nullable = true,
              ),
            ),
          call = "SectionHeader(text = \"\")",
        ),
        record(
          "SessionGroup",
          id = "confetti-wear/session-group",
          parameters =
            listOf(
              TargetParameter(name = "onClick", type = "() -> Unit"),
              TargetParameter(
                name = "label",
                type = "String",
                typeFqn = "kotlin.String",
                hasDefault = true,
              ),
              TargetParameter(name = "expanded", type = "Boolean", typeFqn = "kotlin.Boolean"),
              TargetParameter(
                name = "count",
                type = "Int",
                typeFqn = "kotlin.Int",
                hasDefault = true,
              ),
              TargetParameter(
                name = "content",
                type = "@Composable () -> Unit",
                composableSlot = true,
              ),
            ),
          slots = listOf(ComponentSlot(name = "content", required = true)),
          call = "SessionGroup(onClick = {}, expanded = false, content = {})",
        ),
        record(
          "AvatarPlaceholder",
          id = "confetti-wear/avatar",
          parameters =
            listOf(TargetParameter(name = "name", type = "String", typeFqn = "kotlin.String")),
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
    return ComponentRecord(
      canonicalId = "confetti-wear/${callable}Kt.$name",
      componentIds = listOf(id),
      symbol =
        ComponentSymbol(
          jvmOwner = "dev.johnoreilly.confetti.wear.components.${name}Kt",
          callable = callable,
          name = name,
          origin = ComponentOrigin.PROJECT,
        ),
      parameters = parameters,
      slots = slots,
      code = ComponentCode(call = call, imports = listOf(callable)),
      signatureKnown = true,
    )
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-wear-screen-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }
}
