package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A `remote-m3` component the emitter has no case for, written from the component RECORD.
 *
 * `RemoteContentEmitter.emit` is a `when` over component ids with a hand-written function each, and
 * "twenty-five components means twenty-five functions" was the wrong unit for what that costs. A
 * `remote-material3` component takes Remote Compose values rather than Kotlin ones —
 * `RemoteText(text: RemoteString)` against a design's `"text": "Next train"` — so the missing piece
 * is a mapping from a design's value to a Remote value, one TYPE at a time, and the record already
 * says which type each parameter wants.
 *
 * The spellings are not invented here. `RemoteValueVocabularyProbe` in wear-m3-catalog compiles
 * `"…".rs`, `true.rb`, `0.5f.rf`, `Color(…).rc` and `lambdaAction {}` against remote-material3 and
 * the creation DSL, which is the only place that can: this repository has no Remote Compose
 * dependency anywhere, by design.
 */
class RemoteContentRecordFallbackTest {

  private fun parameter(
    name: String,
    typeFqn: String,
    type: String = typeFqn.substringAfterLast('.'),
    hasDefault: Boolean = false,
    composableSlot: Boolean = false,
  ) =
    TargetParameter(
      name = name,
      type = type,
      typeFqn = typeFqn,
      hasDefault = hasDefault,
      composableSlot = composableSlot,
    )

  private fun record(name: String, vararg parameters: TargetParameter) =
    ComponentRecord(
      canonicalId = "remote-catalog/androidx.wear.compose.remote.material3.${name}Kt.$name",
      componentIds = emptyList(),
      symbol =
        ComponentSymbol(
          jvmOwner = "androidx.wear.compose.remote.material3.${name}Kt",
          callable = "androidx.wear.compose.remote.material3.$name",
          name = name,
          origin = ComponentOrigin.LIBRARY,
        ),
      parameters = parameters.toList(),
      slots = emptyList(),
      signatureKnown = true,
    )

  private val remoteText =
    record(
      "RemoteText",
      parameter("text", "androidx.compose.remote.creation.compose.state.RemoteString"),
      parameter(
        "modifier",
        "androidx.compose.remote.creation.compose.modifier.RemoteModifier",
        hasDefault = true,
      ),
      parameter(
        "color",
        "androidx.compose.remote.creation.compose.state.RemoteColor",
        hasDefault = true,
      ),
    )

  private val remoteButton =
    record(
      "RemoteButton",
      parameter("onClick", "androidx.compose.remote.creation.compose.action.Action"),
      parameter(
        "enabled",
        "androidx.compose.remote.creation.compose.state.RemoteBoolean",
        hasDefault = true,
      ),
      parameter(
        "content",
        "kotlin.Function1",
        type = "RemoteRowScope.() -> Unit",
        composableSlot = true,
      ),
    )

  private fun value(text: String) = buildJsonObject {
    put("type", JsonPrimitive("string"))
    put("value", JsonPrimitive(text))
  }

  private fun stateVariable(valueType: String, initial: JsonPrimitive) = buildJsonObject {
    put("type", JsonPrimitive("value"))
    put("valueType", JsonPrimitive(valueType))
    put("nullable", JsonPrimitive(false))
    put("initialValue", initial)
    put("persistence", JsonPrimitive("preview"))
  }

  private fun action(type: String, variable: String, value: JsonPrimitive? = null) =
    buildJsonObject {
      put("type", JsonPrimitive(type))
      put("variable", JsonPrimitive(variable))
      if (value != null) put("value", value)
    }

  private fun document(
    nodes: Map<String, UiBuilderNode>,
    root: String,
    state: JsonObject = JsonObject(emptyMap()),
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "record-fallback",
      title = "Record fallback",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = state,
      roots = listOf(root),
      nodes = nodes,
    )

  private fun widget(
    child: Map<String, UiBuilderNode>,
    childId: String,
    state: JsonObject = JsonObject(emptyMap()),
  ) =
    document(
      mapOf(
        "host" to
          UiBuilderNode(
            id = "host",
            componentId = "remote-m3/widget-container-small",
            slots = mapOf("content" to listOf(childId)),
          )
      ) + child,
      root = "host",
      state = state,
    )

  @Test
  fun `a component with no case is written from its record`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            widget(
              mapOf(
                "label" to
                  UiBuilderNode(
                    id = "label",
                    componentId = "remote-m3/remote-text",
                    properties = buildJsonObject { put("text", value("Next train")) },
                  )
              ),
              childId = "label",
            ),
            components = mapOf("remote-m3/remote-text" to remoteText),
          )
        )
        .source

    assertTrue("RemoteText(text = \"Next train\".rs)" in source, source)
    assertTrue("import androidx.wear.compose.remote.material3.RemoteText" in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.state.rs" in source, source)
  }

  /**
   * `onClick` with nothing authored is `lambdaAction {}`.
   *
   * Nine of remote-catalog's components require an `Action` and no design carries one, which read
   * as an open product question until it was compiled. A design that says nothing about behaviour
   * means an action that does nothing, and that is a legal `Action`.
   */
  @Test
  fun `a required action with nothing authored is a lambda that does nothing`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            widget(
              mapOf(
                "button" to
                  UiBuilderNode(
                    id = "button",
                    componentId = "remote-m3/remote-button",
                    slots = mapOf("children" to listOf("label")),
                  ),
                "label" to
                  UiBuilderNode(
                    id = "label",
                    componentId = "remote-m3/remote-text",
                    properties = buildJsonObject { put("text", value("Go")) },
                  ),
              ),
              childId = "button",
            ),
            components =
              mapOf(
                "remote-m3/remote-button" to remoteButton,
                "remote-m3/remote-text" to remoteText,
              ),
          )
        )
        .source

    assertTrue("RemoteButton(onClick = lambdaAction {}) {" in source, source)
    assertTrue("RemoteText(text = \"Go\".rs)" in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.action.lambdaAction" in source)
  }

  /**
   * An authored event binding is a `valueChange`, and the variable it writes is declared for it.
   *
   * Every action a document carries is a state write, so `hostAction` maps to nothing that exists
   * and all of them are this. The spellings are compiled in wear-m3-catalog's
   * `RemoteActionVocabularyProbe`; what is asserted here is that a design reaches them.
   */
  @Test
  fun `an authored action writes the design's state`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            widget(
              mapOf(
                "button" to
                  UiBuilderNode(
                    id = "button",
                    componentId = "remote-m3/remote-button",
                    slots = mapOf("children" to listOf("label")),
                    eventBindings =
                      buildJsonObject {
                        put("click", JsonArray(listOf(action("toggle", "expanded"))))
                      },
                  ),
                "label" to
                  UiBuilderNode(
                    id = "label",
                    componentId = "remote-m3/remote-text",
                    properties = buildJsonObject { put("text", value("More")) },
                  ),
              ),
              childId = "button",
              state =
                buildJsonObject { put("expanded", stateVariable("bool", JsonPrimitive(false))) },
            ),
            components =
              mapOf(
                "remote-m3/remote-button" to remoteButton,
                "remote-m3/remote-text" to remoteText,
              ),
          )
        )
        .source

    assertTrue("val expanded = rememberMutableRemoteBoolean(false)" in source, source)
    assertTrue("RemoteButton(onClick = valueChange(expanded, !expanded)) {" in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.action.valueChange" in source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.state.rememberMutableRemoteBoolean" in
        source,
      source,
    )
    // The absent-binding default is gone from this widget: nothing here binds nothing.
    assertTrue("lambdaAction" !in source, source)
  }

  /** An assignment carries the design's value as a Remote value of the declared type. */
  @Test
  fun `a set action assigns the value the design carries`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            widget(
              mapOf(
                "button" to
                  UiBuilderNode(
                    id = "button",
                    componentId = "remote-m3/remote-button",
                    slots = mapOf("children" to listOf("label")),
                    eventBindings =
                      buildJsonObject {
                        put(
                          "click",
                          JsonArray(listOf(action("set", "page", JsonPrimitive(2)))),
                        )
                      },
                  ),
                "label" to
                  UiBuilderNode(
                    id = "label",
                    componentId = "remote-m3/remote-text",
                    properties = buildJsonObject { put("text", value("Next")) },
                  ),
              ),
              childId = "button",
              state = buildJsonObject { put("page", stateVariable("int", JsonPrimitive(0))) },
            ),
            components =
              mapOf(
                "remote-m3/remote-button" to remoteButton,
                "remote-m3/remote-text" to remoteText,
              ),
          )
        )
        .source

    assertTrue("val page = rememberMutableRemoteInt(0)" in source, source)
    assertTrue("RemoteButton(onClick = valueChange(page, 2.ri)) {" in source, source)
  }

  /**
   * `selectOrClear` assigns null, and a Remote value cannot be one. Refused by name rather than
   * given a sentinel, because clearing a selection and setting it to zero are different designs.
   */
  @Test
  fun `clearing a selection is refused by name`() {
    val result =
      WearWidgetCodeExporter.export(
        widget(
          mapOf(
            "button" to
              UiBuilderNode(
                id = "button",
                componentId = "remote-m3/remote-button",
                slots = mapOf("children" to listOf("label")),
                eventBindings =
                  buildJsonObject {
                    put(
                      "click",
                      JsonArray(listOf(action("selectOrClear", "chosen", JsonPrimitive("a")))),
                    )
                  },
              ),
            "label" to
              UiBuilderNode(
                id = "label",
                componentId = "remote-m3/remote-text",
                properties = buildJsonObject { put("text", value("Pick")) },
              ),
          ),
          childId = "button",
          state = buildJsonObject { put("chosen", stateVariable("string", JsonPrimitive(""))) },
        ),
        components =
          mapOf(
            "remote-m3/remote-button" to remoteButton,
            "remote-m3/remote-text" to remoteText,
          ),
      )
    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(
      refused.reasons.any { "clears `chosen`" in it && "cannot be null" in it },
      refused.reasons.toString(),
    )
  }

  /** A write to a variable the design never declared would compile into a write to nothing. */
  @Test
  fun `an action naming an undeclared variable is refused`() {
    val result =
      WearWidgetCodeExporter.export(
        widget(
          mapOf(
            "button" to
              UiBuilderNode(
                id = "button",
                componentId = "remote-m3/remote-button",
                slots = mapOf("children" to listOf("label")),
                eventBindings =
                  buildJsonObject { put("click", JsonArray(listOf(action("toggle", "nowhere")))) },
              ),
            "label" to
              UiBuilderNode(
                id = "label",
                componentId = "remote-m3/remote-text",
                properties = buildJsonObject { put("text", value("Go")) },
              ),
          ),
          childId = "button",
        ),
        components =
          mapOf(
            "remote-m3/remote-button" to remoteButton,
            "remote-m3/remote-text" to remoteText,
          ),
      )
    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(
      refused.reasons.any { "the design does not declare" in it },
      refused.reasons.toString(),
    )
  }

  private fun binding(key: String) = buildJsonObject {
    put("type", JsonPrimitive("binding"))
    put("value", JsonPrimitive(key))
  }

  /**
   * A placed design component is its body, inlined, with the placement's arguments substituted.
   *
   * Inlined rather than emitted as a function, which is what the Compose lane does: a design
   * component's body is ordinary catalog nodes and this emitter can write every one of them. A
   * `RemoteCustomComponent` hole would be actively wrong — the host registers renderers by name and
   * nothing is registered under a design-local key, so the widget would reserve bounds and draw
   * nothing.
   */
  @Test
  fun `a placed design component is inlined with its arguments`() {
    val document =
      widget(
          mapOf(
            "place" to
              UiBuilderNode(
                id = "place",
                componentId = "design/component-instance",
                component =
                  buildJsonObject {
                    put("componentKey", JsonPrimitive("headline"))
                    put("arguments", buildJsonObject { put("caption", value("Next train")) })
                  },
              ),
            "headline-root" to
              UiBuilderNode(
                id = "headline-root",
                componentId = "remote-m3/remote-text",
                properties = buildJsonObject { put("text", binding("caption")) },
              ),
          ),
          childId = "place",
        )
        .copy(
          components =
            buildJsonObject {
              put(
                "headline",
                buildJsonObject {
                  put("name", JsonPrimitive("Headline"))
                  put("root", JsonPrimitive("headline-root"))
                },
              )
            }
        )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            document,
            components = mapOf("remote-m3/remote-text" to remoteText),
          )
        )
        .source

    assertTrue("RemoteText(text = \"Next train\".rs)" in source, source)
    // Inlined, so nothing names the component: no function, no hole, no key.
    assertTrue("headline" !in source, source)
  }

  /** A body reading a key the placement does not supply is a component placed wrongly. */
  @Test
  fun `a binding the placement does not supply is refused`() {
    val document =
      widget(
          mapOf(
            "place" to
              UiBuilderNode(
                id = "place",
                componentId = "design/component-instance",
                component =
                  buildJsonObject {
                    put("componentKey", JsonPrimitive("headline"))
                    put("arguments", JsonObject(emptyMap()))
                  },
              ),
            "headline-root" to
              UiBuilderNode(
                id = "headline-root",
                componentId = "remote-m3/remote-text",
                properties = buildJsonObject { put("text", binding("caption")) },
              ),
          ),
          childId = "place",
        )
        .copy(
          components =
            buildJsonObject {
              put(
                "headline",
                buildJsonObject {
                  put("name", JsonPrimitive("Headline"))
                  put("root", JsonPrimitive("headline-root"))
                },
              )
            }
        )

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          document,
          components = mapOf("remote-m3/remote-text" to remoteText),
        )
      )
    assertTrue(
      refused.reasons.any { "reads `caption`" in it && "does not supply" in it },
      refused.reasons.toString(),
    )
  }

  /** A placement naming a component the design does not define refuses by name. */
  @Test
  fun `a placement of an undefined component is refused`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          widget(
            mapOf(
              "place" to
                UiBuilderNode(
                  id = "place",
                  componentId = "design/component-instance",
                  component = buildJsonObject { put("componentKey", JsonPrimitive("missing")) },
                )
            ),
            childId = "place",
          )
        )
      )
    assertTrue(
      refused.reasons.any { "places `missing`" in it },
      refused.reasons.toString(),
    )
  }

  /**
   * Without a record the refusal is the one it always was, so nothing that has no record changes.
   */
  @Test
  fun `a component with no case and no record is refused by name`() {
    val result =
      WearWidgetCodeExporter.export(
        widget(
          mapOf("label" to UiBuilderNode(id = "label", componentId = "remote-m3/remote-text")),
          childId = "label",
        )
      )
    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(
      refused.reasons.any {
        "remote-m3/remote-text" in it && "no Remote Compose counterpart" in it
      },
      refused.reasons.toString(),
    )
  }

  /**
   * A required parameter the design cannot fill refuses by NAME rather than emitting a call that
   * does not compile. A missing `RemoteFloat` is not a component drawn slightly wrong.
   */
  @Test
  fun `a required parameter the design cannot fill refuses by name`() {
    val slider =
      record(
        "RemoteSlider",
        parameter("value", "androidx.compose.remote.creation.compose.state.RemoteFloat"),
      )
    val result =
      WearWidgetCodeExporter.export(
        widget(
          mapOf("slider" to UiBuilderNode(id = "slider", componentId = "remote-m3/remote-slider")),
          childId = "slider",
        ),
        components = mapOf("remote-m3/remote-slider" to slider),
      )
    val refused = assertIs<WearWidgetCodeExporter.Result.Refused>(result)
    assertTrue(
      refused.reasons.any { "value: RemoteFloat" in it },
      refused.reasons.toString(),
    )
  }
}
