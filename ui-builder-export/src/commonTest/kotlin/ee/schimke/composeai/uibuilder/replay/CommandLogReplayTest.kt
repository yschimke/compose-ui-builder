package ee.schimke.composeai.uibuilder.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandLogReplayTest {
  @Test
  fun `bases chain from the fork point through each landing revision`() {
    val bases = mutableListOf<Long>()
    val replay =
      replayCommandLog(forkRevision = 5, log = listOf("a", "b", "c")) { command, base ->
        bases += base
        ReplayAnswer.Landed(command, base + 10, idempotentReplay = false, conflicts = emptyList())
      }
    assertEquals(listOf(5L, 15L, 25L), bases)
    assertTrue(replay.complete)
    assertNull(replay.stop)
    assertEquals(listOf(15L, 25L, 35L), replay.landed.map { it.committedRevision })
  }

  @Test
  fun `the first refusal stops the run and counts what is left`() {
    var attempted = 0
    val replay =
      replayCommandLog(forkRevision = 1, log = listOf("a", "b", "c", "d")) { command, base ->
        attempted++
        if (command == "b") ReplayAnswer.Refused(command, "REVISION_MISMATCH", "stale")
        else
          ReplayAnswer.Landed(command, base + 1, idempotentReplay = false, conflicts = emptyList())
      }
    assertEquals(2, attempted)
    assertEquals(listOf("a"), replay.landed.map { it.operationId })
    val stop = replay.stop!!
    assertEquals("b", stop.operationId)
    assertEquals(2, stop.baseRevision)
    assertEquals(2, stop.remaining)
  }

  @Test
  fun `a command landing past the caller's highest revision is kept and stops the run`() {
    val replay =
      replayCommandLog(forkRevision = 1, log = listOf("a", "b"), maximumRevision = 9) { command, _
        ->
        ReplayAnswer.Landed(command, 10, idempotentReplay = false, conflicts = emptyList())
      }
    assertEquals(listOf("a"), replay.landed.map { it.operationId })
    assertEquals(REVISION_OVERFLOW, replay.stop!!.code)
    assertEquals(1, replay.stop!!.remaining)
  }
}
