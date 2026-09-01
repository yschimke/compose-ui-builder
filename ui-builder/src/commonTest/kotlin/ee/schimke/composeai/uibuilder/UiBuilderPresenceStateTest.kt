package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.PresenceLeaveV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpsertV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiBuilderPresenceStateTest {
  @Test
  fun `snapshot upsert and leave produce a deterministic remote roster`() {
    val initial = UiBuilderPresenceState().replace(listOf(presence("local"), presence("b")), 10)
    val updated =
      initial
        .apply(PresenceUpsertV1(presence("a", displayName = "Ada")), 20)
        .apply(
          PresenceLeaveV1("b"),
          21,
        )

    assertEquals(listOf("a"), updated.collaborators("local").map { it.actorId })
    assertEquals("Ada", updated.collaborators("local").single().displayName)
  }

  @Test
  fun `presence expires without advancing any durable cursor`() {
    val fresh = UiBuilderPresenceState().replace(listOf(presence("remote")), 100)

    assertEquals(
      1,
      fresh.expire(100 + UI_BUILDER_PRESENCE_EXPIRY_MILLIS - 1).collaborators("local").size,
    )
    assertTrue(
      fresh.expire(100 + UI_BUILDER_PRESENCE_EXPIRY_MILLIS).collaborators("local").isEmpty()
    )
    assertTrue(UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS < UI_BUILDER_PRESENCE_EXPIRY_MILLIS)
  }

  @Test
  fun `the authenticated local actor is never rendered as a collaborator`() {
    val state = UiBuilderPresenceState().replace(listOf(presence("local"), presence("remote")), 0)

    assertEquals(listOf("remote"), state.collaborators("local").map { it.actorId })
  }

  private fun presence(actorId: String, displayName: String = actorId) =
    PresenceV1(
      actorId = actorId,
      clientId = "browser-$actorId",
      displayName = displayName,
      colorArgbHex = "#FF7788AA",
      selectedNodeIds = listOf("node-$actorId"),
      observedRevision = 4,
    )
}
