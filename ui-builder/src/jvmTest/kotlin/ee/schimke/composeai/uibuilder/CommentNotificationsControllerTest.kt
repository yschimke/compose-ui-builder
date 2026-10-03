package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CommentNotificationHost
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsController
import ee.schimke.composeai.uibuilder.editor.CommentNotificationsState
import ee.schimke.composeai.uibuilder.editor.PushAvailability
import ee.schimke.composeai.uibuilder.editor.PushEnvironment
import ee.schimke.composeai.uibuilder.editor.PushHttpResponse
import ee.schimke.composeai.uibuilder.editor.PushPermission
import ee.schimke.composeai.uibuilder.editor.PushSubscriptionInfo
import ee.schimke.composeai.uibuilder.editor.UnavailableCommentNotificationHost
import ee.schimke.composeai.uibuilder.editor.isIosDevice
import ee.schimke.composeai.uibuilder.editor.pushAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "Notify me about replies" against a scripted browser and server.
 *
 * The rules under test are compose-preview-server's (`serve-web/src/push/settings.ts`), so each
 * case names the one it pins: when the switch is not offered at all, what re-posting an existing
 * subscription on load decides, that turning it on keeps the person's other kinds, and that the
 * permission prompt is the click's and never the load's.
 */
class CommentNotificationsControllerTest {
  @Test
  fun hiddenWhenNotHostedByTheServer() = runBlocking {
    val host = FakeHost(environment = READY.copy(hostedByServer = false))
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
    assertTrue(host.calls.isEmpty(), "nothing is asked of a server that is not there")
  }

  @Test
  fun hiddenOnAnInsecureOriginOrWithoutPushManager() = runBlocking {
    for (environment in
      listOf(READY.copy(isSecureContext = false), READY.copy(hasPushManager = false))) {
      val controller = CommentNotificationsController(FakeHost(environment = environment))
      controller.load()
      assertEquals(CommentNotificationsState.Hidden, controller.state.value, "$environment")
    }
  }

  @Test
  fun hiddenWhenTheServerHasNoKey() = runBlocking {
    // Push turned off on the server, or a static host: the key route is not there.
    val host = FakeHost().apply { respond("GET", "/api/push/key", 404) }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
  }

  @Test
  fun hiddenWhenSignedOut() = runBlocking {
    val host = FakeHost().apply { respond("GET", "/api/push/preferences", 401) }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
  }

  @Test
  fun hiddenOnTheJvmAndInPreviews() = runBlocking {
    val controller = CommentNotificationsController(UnavailableCommentNotificationHost)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
  }

  @Test
  fun offWhenAvailableAndNeverAsksOnLoad() = runBlocking {
    val host = FakeHost()
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Toggle(on = false), controller.state.value)
    assertEquals(0, host.permissionRequests)
    assertFalse(host.calls.any { it.startsWith("subscribe") })
  }

  @Test
  fun blockedWhenPermissionWasDenied() = runBlocking {
    val host = FakeHost(permission = PushPermission.Denied)
    val controller = CommentNotificationsController(host)
    controller.load()
    assertTrue(controller.state.value is CommentNotificationsState.Blocked)
    assertEquals(0, host.permissionRequests)
  }

  @Test
  fun iosTabGetsTheInstallHint() = runBlocking {
    val host =
      FakeHost(
        environment =
          READY.copy(
            hasPushManager = false,
            hasNotification = false,
            userAgent = IPHONE_SAFARI,
            maxTouchPoints = 5,
          )
      )
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.InstallFirst, controller.state.value)
  }

  @Test
  fun iosHintIsAlsoHiddenWhenThereIsNoKey() = runBlocking {
    val host =
      FakeHost(environment = READY.copy(hasPushManager = false, userAgent = IPHONE_SAFARI)).apply {
        respond("GET", "/api/push/key", 404)
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
  }

  @Test
  fun availabilityMirrorsServeWeb() {
    assertEquals(PushAvailability.Ready, pushAvailability(READY))
    assertEquals(PushAvailability.Insecure, pushAvailability(READY.copy(isSecureContext = false)))
    assertEquals(
      PushAvailability.InstallFirst,
      pushAvailability(READY.copy(hasPushManager = false, userAgent = IPHONE_SAFARI)),
    )
    // Installed to the Home Screen but still without push: an iOS older than 16.4.
    assertEquals(
      PushAvailability.Unsupported,
      pushAvailability(
        READY.copy(hasPushManager = false, userAgent = IPHONE_SAFARI, standalone = true)
      ),
    )
    // The iPad that says it is a Mac.
    assertTrue(isIosDevice(IPAD_AS_MAC, maxTouchPoints = 5))
    assertFalse(isIosDevice(IPAD_AS_MAC, maxTouchPoints = 0))
  }

  @Test
  fun existingSubscriptionIsRepostedWithoutKindsBeforeShowingOn() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond(
          "POST",
          "/api/push/subscribe",
          201,
          """{"kinds":["mentions","replies"],"devices":1}""",
        )
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Toggle(on = true), controller.state.value)
    val post = host.bodies.getValue("POST /api/push/subscribe").single()
    val json = Json.parseToJsonElement(post).jsonObject
    assertEquals(SUBSCRIPTION.endpoint, json.getValue("endpoint").jsonPrimitive.content)
    assertNull(json["kinds"], "a rebind keeps the person's own kinds")
  }

  @Test
  fun existingSubscriptionForOtherKindsShowsOff() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond("POST", "/api/push/subscribe", 201, """{"kinds":["reviews"],"devices":1}""")
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Toggle(on = false), controller.state.value)
  }

  @Test
  fun aRefusedRebindUnsubscribes() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond("POST", "/api/push/subscribe", 422, """{"error":"too many devices"}""")
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    val state = controller.state.value as CommentNotificationsState.Toggle
    assertFalse(state.on)
    assertTrue(state.message!!.contains("too many devices"))
    assertNull(host.subscription, "a 4xx ends the subscription in the browser too")
  }

  @Test
  fun aSignedOutRebindUnsubscribesAndHides() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond("POST", "/api/push/subscribe", 401)
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    assertEquals(CommentNotificationsState.Hidden, controller.state.value)
    assertNull(host.subscription)
  }

  @Test
  fun aServerErrorOnRebindKeepsTheSubscription() = runBlocking {
    for (status in listOf(503, 0)) {
      val host =
        FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
          respond("POST", "/api/push/subscribe", status)
        }
      val controller = CommentNotificationsController(host)
      controller.load()
      val state = controller.state.value as CommentNotificationsState.Toggle
      assertFalse(state.on)
      assertTrue(state.message!!.startsWith("Could not confirm"), "$status")
      assertEquals(SUBSCRIPTION, host.subscription, "a $status is not a refusal")
    }
  }

  @Test
  fun turningOnAsksFirstAndKeepsTheOtherKinds() = runBlocking {
    val host =
      FakeHost().apply {
        respond("GET", "/api/push/preferences", 200, """{"kinds":["reviews"],"devices":1}""")
        respond(
          "POST",
          "/api/push/subscribe",
          201,
          """{"kinds":["mentions","replies","reviews"],"devices":2}""",
        )
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Granted
    host.calls.clear()
    controller.toggle()
    assertEquals(CommentNotificationsState.Toggle(on = true), controller.state.value)
    assertEquals("requestPermission", host.calls.first(), "the prompt comes before anything else")
    assertEquals("subscribe $KEY", host.calls.first { it.startsWith("subscribe") })
    val kinds =
      Json.parseToJsonElement(host.bodies.getValue("POST /api/push/subscribe").single())
        .jsonObject
        .getValue("kinds")
        .jsonArray
        .map { it.jsonPrimitive.content }
    assertEquals(listOf("mentions", "replies", "reviews"), kinds)
  }

  @Test
  fun turningOnWithNoDeviceYetIsOnlyReplies() = runBlocking {
    // With nothing subscribed the server answers its default — every kind — which nobody chose.
    val host =
      FakeHost().apply {
        respond(
          "GET",
          "/api/push/preferences",
          200,
          """{"kinds":["mentions","replies","reviews"],"devices":0}""",
        )
        respond(
          "POST",
          "/api/push/subscribe",
          201,
          """{"kinds":["mentions","replies"],"devices":1}""",
        )
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Granted
    controller.toggle()
    val kinds =
      Json.parseToJsonElement(host.bodies.getValue("POST /api/push/subscribe").single())
        .jsonObject
        .getValue("kinds")
        .jsonArray
        .map { it.jsonPrimitive.content }
    assertEquals(listOf("mentions", "replies"), kinds)
  }

  @Test
  fun turningOnAnExistingSubscriptionForReviewsAddsRepliesWithPut() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond("POST", "/api/push/subscribe", 201, """{"kinds":["reviews"],"devices":1}""")
        respond(
          "PUT",
          "/api/push/preferences",
          200,
          """{"kinds":["mentions","replies","reviews"],"devices":1}""",
        )
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Granted
    controller.toggle()
    assertEquals(CommentNotificationsState.Toggle(on = true), controller.state.value)
    val put = host.bodies.getValue("PUT /api/push/preferences").single()
    assertEquals("""{"kinds":["mentions","replies","reviews"]}""", put)
    assertFalse(host.calls.any { it.startsWith("subscribe") })
  }

  @Test
  fun denyingThePromptShowsBlocked() = runBlocking {
    val host = FakeHost()
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Denied
    controller.toggle()
    assertTrue(controller.state.value is CommentNotificationsState.Blocked)
    assertTrue(host.bodies.isEmpty(), "nothing is sent after a refusal")
  }

  @Test
  fun aFailedSubscribePostUnsubscribesTheBrowser() = runBlocking {
    val host =
      FakeHost().apply {
        respond("POST", "/api/push/subscribe", 422, """{"error":"bad endpoint"}""")
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Granted
    controller.toggle()
    val state = controller.state.value as CommentNotificationsState.Toggle
    assertFalse(state.on)
    assertTrue(state.message!!.contains("bad endpoint"))
    assertNull(host.subscription)
  }

  @Test
  fun turningOffDeletesTheSubscriptionAndTheWorker() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond(
          "POST",
          "/api/push/subscribe",
          201,
          """{"kinds":["mentions","replies"],"devices":1}""",
        )
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    controller.toggle()
    assertEquals(CommentNotificationsState.Toggle(on = false), controller.state.value)
    assertEquals(
      """{"endpoint":"${SUBSCRIPTION.endpoint}"}""",
      host.bodies.getValue("DELETE /api/push/subscribe").single(),
    )
    assertNull(host.subscription)
    assertTrue("unregisterWorker" in host.calls)
    assertEquals(0, host.permissionRequests, "turning off never prompts")
  }

  @Test
  fun turningOffKeepsReviewsAndTheSubscription() = runBlocking {
    val host =
      FakeHost(permission = PushPermission.Granted, subscription = SUBSCRIPTION).apply {
        respond(
          "POST",
          "/api/push/subscribe",
          201,
          """{"kinds":["mentions","replies","reviews"],"devices":1}""",
        )
        respond("PUT", "/api/push/preferences", 200, """{"kinds":["reviews"],"devices":1}""")
      }
    val controller = CommentNotificationsController(host)
    controller.load()
    controller.toggle()
    assertEquals(CommentNotificationsState.Toggle(on = false), controller.state.value)
    assertEquals(
      """{"kinds":["reviews"]}""",
      host.bodies.getValue("PUT /api/push/preferences").single(),
    )
    assertNotNull(host.subscription)
    assertFalse("DELETE /api/push/subscribe" in host.bodies)
  }

  @Test
  fun aBrowserFailureIsASentenceNotACrash() = runBlocking {
    val host = FakeHost().apply { subscribeFailure = "Registration failed - permission denied" }
    val controller = CommentNotificationsController(host)
    controller.load()
    host.permissionAnswer = PushPermission.Granted
    controller.toggle()
    val state = controller.state.value as CommentNotificationsState.Toggle
    assertFalse(state.on)
    assertTrue(state.message!!.contains("Registration failed"))
  }

  private class FakeHost(
    override val environment: PushEnvironment = READY,
    var permission: PushPermission = PushPermission.Default,
    var subscription: PushSubscriptionInfo? = null,
  ) : CommentNotificationHost {
    val calls = mutableListOf<String>()
    val bodies = mutableMapOf<String, MutableList<String>>()
    var permissionRequests = 0
    var permissionAnswer = PushPermission.Default
    var subscribeFailure: String? = null
    private val responses =
      mutableMapOf(
        "GET /api/push/key" to PushHttpResponse(200, """{"publicKey":"$KEY","kinds":[]}"""),
        "GET /api/push/preferences" to
          PushHttpResponse(200, """{"kinds":["mentions","replies","reviews"],"devices":0}"""),
        "POST /api/push/subscribe" to
          PushHttpResponse(201, """{"kinds":["mentions","replies"],"devices":1}"""),
        "DELETE /api/push/subscribe" to PushHttpResponse(200, """{"kinds":[],"devices":0}"""),
      )

    fun respond(method: String, path: String, status: Int, body: String = "") {
      responses["$method $path"] = PushHttpResponse(status, body)
    }

    override fun permission(): PushPermission = permission

    override suspend fun requestPermission(): PushPermission {
      calls += "requestPermission"
      permissionRequests++
      permission = permissionAnswer
      return permissionAnswer
    }

    override suspend fun existingSubscription(): PushSubscriptionInfo? = subscription

    override suspend fun subscribe(publicKey: String): PushSubscriptionInfo {
      calls += "subscribe $publicKey"
      subscribeFailure?.let { error(it) }
      return (subscription ?: SUBSCRIPTION).also { subscription = it }
    }

    override suspend fun unsubscribe() {
      calls += "unsubscribe"
      subscription = null
    }

    override suspend fun unregisterWorker() {
      calls += "unregisterWorker"
    }

    override suspend fun request(method: String, path: String, body: String?): PushHttpResponse {
      calls += "$method $path"
      body?.let { bodies.getOrPut("$method $path") { mutableListOf() } += it }
      return responses["$method $path"] ?: PushHttpResponse(404)
    }
  }

  private companion object {
    const val KEY = "BPublicKeyBase64Url"
    val SUBSCRIPTION =
      PushSubscriptionInfo("https://fcm.googleapis.com/fcm/send/abc", "p256dh-key", "auth-secret")
    val READY =
      PushEnvironment(
        hostedByServer = true,
        isSecureContext = true,
        hasServiceWorker = true,
        hasPushManager = true,
        hasNotification = true,
        userAgent = "Mozilla/5.0 (X11; Linux x86_64) Chrome/130",
      )
    const val IPHONE_SAFARI =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1"
    const val IPAD_AS_MAC =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15"
  }
}
