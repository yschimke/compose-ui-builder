package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.UndoCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What survives a reload, and what a full browser costs.
 *
 * The point of storing a log rather than a document is that the undo stack is part of what a person
 * keeps; the point of compaction is that a log cannot grow forever inside a five-megabyte origin.
 * Both are asserted here rather than described, because both are invisible until the day they are
 * wrong.
 */
class LocalDesignSessionTest {
  private val storage = InMemoryLocalDesignStorage()
  private val store = LocalDesignStore(storage)

  private fun open(record: LocalDesignRecordV1, thresholdBytes: Int = 1_000_000) =
    LocalDesignSession.open(
      record = record,
      store = store,
      clock = { 1_700_000_000_000 },
      compactionThresholdBytes = thresholdBytes,
    )

  private fun seeded(): LocalDesignRecordV1 =
    localDesignRecord(
      document = LocalDesignFixtures.document(),
      catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
      sequence = 0,
      nowEpochMillis = 0,
    )

  @Test
  fun `an accepted edit is written, and a reload replays it`() {
    val session = open(seeded())

    val result = session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))

    assertIs<CommandOutcome.Accepted>(result.outcome)
    assertEquals(LocalPersistence.Stored, result.persistence)
    assertEquals(1, result.sequence)
    assertTrue("first" in session.document.nodes)

    val reloaded = open(assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID)))

    assertTrue("first" in reloaded.document.nodes)
    assertEquals(session.document.revision, reloaded.document.revision)
    assertEquals(1, reloaded.sequence)
    assertEquals(emptyList(), reloaded.replayRefusals)
  }

  @Test
  fun `the undo stack is part of what a reload keeps`() {
    val session = open(seeded())
    session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))

    // Undo is a compensation the reducer computes from the command it names, so it only means
    // something to a state that has that command in its history. Replaying the log is what puts it
    // there; a stored document alone could not answer this.
    val reloaded = open(assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID)))
    val undo =
      reloaded.submit(
        LocalSubmissionRecordV1.Undo(
          UndoCommand(
            designId = LocalDesignFixtures.DESIGN_ID,
            operationId = "undo-1",
            actorId = "tester",
            clientId = "test-client",
            baseRevision = reloaded.document.revision,
            targetOperationId = "op-first",
          )
        )
      )

    assertIs<CommandOutcome.Accepted>(undo.outcome)
    assertTrue("first" !in reloaded.document.nodes)
    assertTrue(
      "first" !in open(assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID))).document.nodes
    )
  }

  @Test
  fun `a rejected edit changes nothing durable`() {
    val session = open(seeded())
    session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))
    val before = store.read(LocalDesignFixtures.DESIGN_ID)

    // A delete of a node nothing has. Stale inserts and property writes converge on purpose — see
    // the reducer — so a refusal has to be asked for by naming something that does not exist.
    val refused = session.submit(LocalDesignFixtures.deleteMissing("op-missing", baseRevision = 1))

    assertIs<CommandOutcome.Rejected>(refused.outcome)
    assertEquals(before, store.read(LocalDesignFixtures.DESIGN_ID))
  }

  @Test
  fun `a log past its budget is compacted onto the document it produced`() {
    // Small enough that the second edit crosses it, which is the whole of what a real quota does
    // more slowly.
    val session = open(seeded(), thresholdBytes = 1)

    val first = session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))

    assertIs<LocalPersistence.Compacted>(first.persistence)
    val stored = assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID))
    assertEquals(emptyList(), stored.log)
    // The design itself is intact, and the durable sequence did not rewind — a client that had
    // already seen sequence 1 must not be handed a snapshot claiming 0.
    assertTrue("first" in stored.seed.nodes)
    assertEquals(1, stored.seedSequence)
    assertEquals(1, open(stored).sequence)
  }

  @Test
  fun `a browser with no room keeps the edit on screen and says the design is not stored`() {
    val full = InMemoryLocalDesignStorage(quotaBytes = 1)
    val session =
      LocalDesignSession.open(record = seeded(), store = LocalDesignStore(full), clock = { 0 })

    val result = session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))

    assertIs<CommandOutcome.Accepted>(result.outcome)
    assertIs<LocalPersistence.Refused>(result.persistence)
    assertTrue("first" in session.document.nodes)
  }

  @Test
  fun `a log that no longer replays opens at the last revision that did`() {
    val session = open(seeded())
    session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))
    val stored = assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID))

    // A command naming a node the design does not have: what a record edited by hand, or written
    // by a builder whose reducer differed, looks like from here.
    val corrupt =
      stored.copy(
        log = stored.log + LocalDesignFixtures.deleteMissing("op-broken", baseRevision = 1)
      )

    val reopened = open(corrupt)

    assertTrue("first" in reopened.document.nodes)
    assertEquals(1, reopened.replayRefusals.size)
  }
}
