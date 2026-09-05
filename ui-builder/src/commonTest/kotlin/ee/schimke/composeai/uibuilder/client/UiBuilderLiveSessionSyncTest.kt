package ee.schimke.composeai.uibuilder.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiBuilderLiveSessionSyncTest {
  @Test
  fun `nothing is editable before the first snapshot`() {
    assertNull(UiBuilderLiveSessionSync().baseRevision)
  }

  @Test
  fun `a snapshot is displayed and becomes the base when nothing is queued`() {
    val sync = UiBuilderLiveSessionSync()

    assertEquals(SnapshotDisposition.DISPLAY, sync.receiveSnapshot(sequence = 4, revision = 7))
    assertEquals(7, sync.baseRevision)
  }

  @Test
  fun `an answer overtaken by a newer one is dropped rather than rolling the canvas back`() {
    val sync = UiBuilderLiveSessionSync()
    sync.receiveSnapshot(sequence = 9, revision = 12)

    assertEquals(SnapshotDisposition.STALE, sync.receiveSnapshot(sequence = 5, revision = 8))
    assertEquals(12, sync.baseRevision)
  }

  @Test
  fun `a queued edit holds the snapshot back but still advances the base revision`() {
    val sync = UiBuilderLiveSessionSync()
    sync.receiveSnapshot(sequence = 1, revision = 1)
    sync.enqueueSubmission()

    assertEquals(SnapshotDisposition.DEFER, sync.receiveSnapshot(sequence = 2, revision = 2))
    // The held snapshot is not on screen, and the next command still has to be based on it.
    assertEquals(2, sync.baseRevision)

    sync.completeSubmission()
    assertTrue(sync.releaseDeferredSnapshot())
    assertFalse(sync.releaseDeferredSnapshot())
  }

  @Test
  fun `a burst chains each command onto the revision the previous one produced`() {
    val sync = UiBuilderLiveSessionSync()
    sync.receiveSnapshot(sequence = 0, revision = 0)
    repeat(20) { sync.enqueueSubmission() }

    val claimed =
      (1..20).map { edit ->
        val base = sync.baseRevision
        // The drain loop's round trip: the server commits at base + 1 and answers with it.
        sync.receiveSnapshot(sequence = edit.toLong(), revision = edit)
        sync.completeSubmission()
        base
      }

    assertEquals((0..19).toList(), claimed)
    assertEquals(0, sync.pendingSubmissions)
    assertEquals(20, sync.baseRevision)
    assertTrue(sync.releaseDeferredSnapshot())
  }

  @Test
  fun `the queue draining with nothing held leaves the canvas alone`() {
    val sync = UiBuilderLiveSessionSync()
    sync.receiveSnapshot(sequence = 1, revision = 1)
    sync.enqueueSubmission()
    sync.completeSubmission()

    assertFalse(sync.releaseDeferredSnapshot())
  }

  @Test
  fun `a snapshot held during a burst is not released while another edit is still queued`() {
    val sync = UiBuilderLiveSessionSync()
    sync.receiveSnapshot(sequence = 1, revision = 1)
    sync.enqueueSubmission()
    sync.enqueueSubmission()
    sync.receiveSnapshot(sequence = 2, revision = 2)

    sync.completeSubmission()
    assertFalse(sync.releaseDeferredSnapshot())

    sync.completeSubmission()
    assertTrue(sync.releaseDeferredSnapshot())
  }
}
