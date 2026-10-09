package ee.schimke.composeai.uibuilder.guidelines

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DesignGuidelinesTest {
  @Test
  fun `the embedded rules are the docs file, and every rule is complete and uniquely named`() {
    val file = File("../docs/guidelines/android-design-guidelines.json").readText()
    // Embedded at build time by :ui-builder-export:embedDesignGuidelines, so they cannot drift.
    assertEquals(
      Json { ignoreUnknownKeys = true }.decodeFromString(DesignGuidelineRuleSet.serializer(), file),
      DesignGuidelineRuleSet.Bundled,
    )
    val rules = DesignGuidelineRuleSet.Bundled.rules
    assertEquals(rules.size, rules.map { it.id }.toSet().size)
    rules.forEach { rule ->
      assertTrue(rule.kind == "structure" || rule.kind == "visual", rule.id)
      assertTrue(rule.severity == "warning" || rule.severity == "info", rule.id)
      assertTrue('?' in rule.check, rule.id)
      assertTrue(RULE_SOURCES.any { rule.source.startsWith(it) }, rule.id)
    }
    assertTrue(DesignGuidelineRuleSet.Bundled.forPlatform("wear").isNotEmpty())
    assertTrue(DesignGuidelineRuleSet.Bundled.forPlatform("glasses").isNotEmpty())
  }

  @Test
  fun `the platform comes from the catalog`() {
    assertEquals("wear", DesignGuidelinePrompt.platformOf("wear-m3"))
    assertEquals("wear", DesignGuidelinePrompt.platformOf("remote-m3"))
    assertEquals("glasses", DesignGuidelinePrompt.platformOf("glimmer"))
    assertEquals("mobile", DesignGuidelinePrompt.platformOf("m3"))
    assertNull(DesignGuidelinePrompt.platformOf("compose-foundation"))
  }

  @Test
  fun `the outline is the tree a model reads, and the request carries the rules`() {
    val encoded = DesignGuidelineController.encode(wearDocument())
    val outline = DesignGuidelinePrompt.outline(encoded)
    assertTrue("- screen: wear-m3/screen-scaffold {timeText=\"10:10\"}" in outline, outline)
    assertTrue("- stop: wear-m3/button {label=\"Stop\"} modifiers[width(value=80)]" in outline)

    val rules = DesignGuidelineRuleSet.Bundled.forPlatform("wear", "screen").filterNot { it.visual }
    val request =
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "workout",
        3,
        encoded,
        null,
        null,
      )
    assertEquals(rules.map { it.id }, request.rules.asked.map { it.id })
    val body = DesignGuidelinePrompt.body(request, "m")
    val content = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
    assertEquals(1, content.size)
    val text = content.single().jsonObject["text"]!!.jsonPrimitive.content
    rules.forEach { assertTrue("ruleId: ${it.id}" in text, it.id) }
    assertEquals("m", body["model"]!!.jsonPrimitive.content)
  }

  @Test
  fun `confident failures on asked rules become findings on nodes that exist`() {
    val asked = DesignGuidelineRuleSet.Bundled.forPlatform("wear")
    val findings =
      DesignGuidelinePrompt.findings(
        DesignGuidelinePrompt.parseVerdicts(
          """
          {"verdicts":[
            {"ruleId":"wear.layout.responsive-width","verdict":"fail","confidence":0.8,
              "nodeIds":["stop","ghost"],"reason":"Fixed width."},
            {"ruleId":"wear.touch-target-48dp","verdict":"fail","confidence":0.2,"nodeIds":[],"reason":"?"},
            {"ruleId":"glasses.lists.one-per-view","verdict":"fail","confidence":0.9,"nodeIds":[],"reason":"!"},
            {"ruleId":"wear.layout.time-text-shown","verdict":"pass","confidence":0.9,"nodeIds":[],"reason":""}
          ]}
          """
        ),
        asked,
        setOf("screen", "stop"),
      )
    val finding = findings.single()
    assertEquals("wear.layout.responsive-width", finding.rule.id)
    assertEquals(listOf("stop"), finding.nodeIds)
    assertEquals(80, finding.confidencePercent)
  }

  @Test
  fun `a check without a picture leaves the visual rules out and says how many`() = runBlocking {
    val host = FakeHost(key = "sk-or-1")
    var sent = ""
    host.respond = { body ->
      sent = body
      DesignGuidelineHost.Response(
        200,
        completion(
          """{"verdicts":[{"ruleId":"wear.layout.time-text-shown","verdict":"pass",""" +
            """"confidence":0.9,"nodeIds":[],"reason":""}]}"""
        ),
      )
    }
    val controller = DesignGuidelineController(host)
    val document = wearDocument()

    controller.check(document, DesignGuidelineController.encode(document))

    val ready = assertIs<DesignGuidelineState.Ready>(controller.state.value)
    val result = ready.result!!
    val visual = DesignGuidelineRuleSet.Bundled.forPlatform("wear", "screen").filter { it.visual }
    assertEquals(visual.size, result.visualSkipped)
    visual.forEach { assertFalse(it.id in sent, it.id) }
    assertEquals("sk-or-1", host.lastKey)
    assertEquals(DEFAULT_GUIDELINE_MODEL, result.model)
    // One rule answered: the rest are unanswered, which is unchecked rather than passed.
    assertEquals(1, result.judged)
    val structural =
      DesignGuidelineRuleSet.Bundled.forPlatform("wear", "screen").filterNot { it.visual }
    assertEquals(structural.size - 1, result.unanswered.size)

    // No verdict for any rule asked is a failure, not a clean result.
    host.respond = { DesignGuidelineHost.Response(200, completion("""{"verdicts":[]}""")) }
    controller.check(document, DesignGuidelineController.encode(document))
    val failed = assertIs<DesignGuidelineState.Ready>(controller.state.value)
    assertTrue("no verdict" in failed.notice!!, failed.notice)
  }

  @Test
  fun `a component's body is outlined once, and each placement names it`() {
    val document =
      Json.parseToJsonElement(
          """
          {
            "roots": ["screen"],
            "components": {"card": {"name": "Card", "root": "card-root"}},
            "nodes": {
              "screen": {"componentId": "layout/column", "slots": {"children": ["a"]}},
              "a": {"componentId": "design/component-instance",
                "component": {"componentKey": "card",
                  "arguments": {"title": {"type": "string", "value": "Hi"}}}},
              "card-root": {"componentId": "wear-m3/card", "slots": {"content": ["t"]}},
              "t": {"componentId": "wear-m3/text",
                "properties": {"text": {"type": "binding", "value": "title"}}}
            }
          }
          """
        )
        .jsonObject
    val outline = DesignGuidelinePrompt.outline(document)
    assertTrue("- a: design/component-instance instance of card (title=\"Hi\")" in outline, outline)
    assertTrue("component card (Card):\n  - card-root: wear-m3/card" in outline, outline)
    assertFalse("more nodes not shown" in outline, outline)
  }

  @Test
  fun `a refused key is forgotten, and other refusals are a sentence`() = runBlocking {
    val host = FakeHost(key = "sk-or-bad")
    host.respond = {
      DesignGuidelineHost.Response(401, """{"error":{"message":"No auth credentials found"}}""")
    }
    val controller = DesignGuidelineController(host)
    controller.check(wearDocument(), DesignGuidelineController.encode(wearDocument()))
    val needs = assertIs<DesignGuidelineState.NeedsKey>(controller.state.value)
    assertTrue("No auth credentials" in needs.notice!!, needs.notice)
    assertNull(host.storedKey())

    controller.saveKey(" sk-or-good ")
    assertEquals("sk-or-good", host.storedKey())
    host.respond = { DesignGuidelineHost.Response(402, """{"error":{"message":"No credits"}}""") }
    controller.check(wearDocument(), DesignGuidelineController.encode(wearDocument()))
    val ready = assertIs<DesignGuidelineState.Ready>(controller.state.value)
    assertTrue("No credits" in ready.notice!!, ready.notice)
    assertFalse(ready.running)
  }

  @Test
  fun `the generated source goes with the tree when the host can export it`(): Unit = runBlocking {
    val host = FakeHost(key = "sk-or-1")
    var sent = ""
    host.respond = { body ->
      sent = body
      DesignGuidelineHost.Response(
        200,
        completion(
          """{"verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"pass",""" +
            """"confidence":0.9,"nodeIds":[],"reason":""}]}"""
        ),
      )
    }
    val controller = DesignGuidelineController(host)
    val document = wearDocument()

    controller.check(document, DesignGuidelineController.encode(document))
    val without = assertIs<DesignGuidelineState.Ready>(controller.state.value).result!!
    assertFalse(without.sourceAttached)
    assertFalse("```kotlin" in sent)

    host.sourceText = "@Composable fun Workout() { Button(Modifier.width(80.dp)) {} }"
    controller.check(document, DesignGuidelineController.encode(document))
    val with = assertIs<DesignGuidelineState.Ready>(controller.state.value).result!!
    assertTrue(with.sourceAttached)
    assertTrue("Modifier.width(80.dp)" in sent, sent)
  }

  @Test
  fun `a sign-in OpenRouter returned from stores the key`() = runBlocking {
    val host = FakeHost(key = null)
    host.signInResult = DesignGuidelineHost.SignInResult.Signed("sk-or-pkce")
    val controller = DesignGuidelineController(host)
    assertIs<DesignGuidelineState.NeedsKey>(controller.state.value)
    controller.completeSignIn()
    assertIs<DesignGuidelineState.Ready>(controller.state.value)
    assertEquals("sk-or-pkce", host.storedKey())
  }

  @Test
  fun `the prompt is shown without a key, and says where each part comes from`(): Unit =
    runBlocking {
      val host = FakeHost(key = null)
      val controller = DesignGuidelineController(host)
      val document = wearDocument()

      controller.preview(document, DesignGuidelineController.encode(document))

      val shown = assertIs<DesignGuidelineController.PromptView.Shown>(controller.prompt.value)
      val request = shown.request
      assertEquals(DesignGuidelineRequest.SCHEMA, request.schema)
      assertEquals("wear", request.platform)
      assertEquals(GUIDELINE_RULES_URL, request.rules.source)
      assertTrue(
        request.provenance.any { "No picture is attached" in it },
        request.provenance.toString(),
      )
      assertTrue(request.provenance.any { "No Compose source" in it })
      assertTrue("ruleId: wear.layout.responsive-width" in request.userText)
      assertNull(host.lastKey, "a preview spends no key")
      controller.hidePrompt()
      assertIs<DesignGuidelineController.PromptView.Hidden>(controller.prompt.value)
    }

  @Test
  fun `the host's request is preferred, and a run is recorded for everyone`(): Unit = runBlocking {
    val host = FakeHost(key = "sk-or-1")
    val document = wearDocument()
    val local =
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "workout",
        3,
        DesignGuidelineController.encode(document),
        "data:image/png;base64,AAAA",
        null,
      )
    host.hosted =
      local.copy(
        pictures =
          local.pictures +
            DesignGuidelinePicture(
              DesignGuidelinePicture.UNROLLED,
              "Picture 2 (unrolled picture)",
              192,
              768,
              "data:image/png;base64,BBBB",
            )
      )
    var sent = ""
    host.respond = { body ->
      sent = body
      DesignGuidelineHost.Response(
        200,
        completion(
          """{"verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail",""" +
            """"confidence":0.9,"nodeIds":["stop"],"reason":"Fixed width."}]}"""
        ),
      )
    }
    val controller = DesignGuidelineController(host)

    controller.check(document, DesignGuidelineController.encode(document))

    assertTrue("BBBB" in sent && "AAAA" in sent, "both pictures go to the model")
    val recorded = host.recorded!!
    assertEquals(3, recorded.revision)
    assertEquals(listOf("wear.layout.responsive-width"), recorded.verdicts.map { it.ruleId })
    val result = assertIs<DesignGuidelineState.Ready>(controller.state.value).result!!
    assertEquals("github:someone", result.ranBy)
    assertEquals(host.hosted, result.request)
    assertEquals("github:someone", controller.shared.value!!.ranBy)
  }

  @Test
  fun `a recorded result reads back as findings`(): Unit = runBlocking {
    val host = FakeHost(key = null)
    host.recorded =
      DesignGuidelineRecord(
        designId = "workout",
        revision = 2,
        model = "anthropic/claude-haiku-5.5",
        rulesVersion = 3,
        asked = listOf("wear.layout.responsive-width", "wear.from-a-newer-set"),
        verdicts =
          DesignGuidelinePrompt.parseVerdicts(
            """
            {"verdicts":[
              {"ruleId":"wear.layout.responsive-width","verdict":"fail","confidence":0.8,
                "nodeIds":["stop"],"reason":"Fixed width."},
              {"ruleId":"wear.from-a-newer-set","verdict":"fail","confidence":0.9,
                "nodeIds":[],"reason":"Kept."}
            ]}
            """
          ),
        ranBy = "agent:review-bot",
      )
    val controller = DesignGuidelineController(host)

    controller.loadShared()

    val shared = controller.shared.value!!
    assertEquals("agent:review-bot", shared.ranBy)
    assertEquals(2, shared.revision)
    assertEquals(
      listOf("wear.layout.responsive-width", "wear.from-a-newer-set"),
      shared.findings.map { it.rule.id },
    )
  }

  private class FakeHost(key: String?) : DesignGuidelineHost {
    private var key: String? = key
    private var model: String? = null
    var lastKey: String? = null
    var respond: (String) -> DesignGuidelineHost.Response = { error("no response set") }
    var signInResult: DesignGuidelineHost.SignInResult =
      DesignGuidelineHost.SignInResult.NotReturning

    override fun storedKey() = key

    override fun storeKey(key: String?) {
      this.key = key
    }

    override fun storedModel() = model

    override fun storeModel(model: String?) {
      this.model = model
    }

    override val signIn: (() -> Unit)? = null

    override suspend fun completeSignIn() = signInResult

    override suspend fun complete(body: String, key: String): DesignGuidelineHost.Response {
      lastKey = key
      return respond(body)
    }

    override suspend fun picture(document: UiBuilderDocument): String? = null

    var sourceText: String? = null

    override suspend fun source(document: UiBuilderDocument): String? = sourceText

    var hosted: DesignGuidelineRequest? = null
    var recorded: DesignGuidelineRecord? = null

    override suspend fun hostedRequest(document: UiBuilderDocument) = hosted

    override suspend fun sharedResult() = recorded

    override suspend fun recordResult(record: DesignGuidelineRecord): DesignGuidelineRecord {
      recorded = record.copy(ranBy = "github:someone", recordedAtEpochMillis = 1L)
      return recorded!!
    }
  }

  private fun completion(content: String): String = buildJsonObject {
    put(
      "choices",
      buildJsonArray {
        add(
          buildJsonObject {
            put(
              "message",
              buildJsonObject {
                put("role", "assistant")
                put("content", content)
              },
            )
          }
        )
      },
    )
  }
    .toString()

  private fun wearDocument(): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "workout",
        "title": "Workout",
        "revision": 3,
        "catalogPin": {"systemId": "wear-m3"},
        "environment": {"widthDp": 192, "heightDp": 192, "theme": "dark"},
        "stateVariables": {},
        "roots": ["screen"],
        "nodes": {
          "screen": {"id": "screen", "componentId": "wear-m3/screen-scaffold",
            "properties": {"timeText": {"type": "string", "value": "10:10"}},
            "slots": {"content": ["stop"]}},
          "stop": {"id": "stop", "componentId": "wear-m3/button",
            "properties": {"label": {"type": "string", "value": "Stop"}},
            "modifiers": [{"type": "width", "value": 80}]}
        }
      }
      """,
    )

  @Suppress("unused")
  private fun JsonObject.text(name: String) = (this[name] as JsonPrimitive).content
}

/**
 * Where a rule's guidance may be quoted from: the design guides, the Material 3 site, the Android
 * Knowledge Base, the Wear OS Material 3 Figma kit, and the androidx sources whose KDoc a rule
 * quotes.
 */
private val RULE_SOURCES =
  listOf(
    "https://developer.android.com/",
    "https://m3.material.io/",
    "kb://",
    "https://www.figma.com/design/",
    "https://github.com/androidx/androidx/blob/androidx-main/",
  )
