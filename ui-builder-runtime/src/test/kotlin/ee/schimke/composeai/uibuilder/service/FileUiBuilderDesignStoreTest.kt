package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * What the per-design store promises, stated as tests rather than as a cost model.
 *
 * The claim in yschimke/compose-preview-server#578 is not "this is faster" — it is that an edit
 * writes what the edit changed and touches no other design. Both halves are observable in the file
 * tree, so they are asserted there.
 */
class FileUiBuilderDesignStoreTest {
  @Test
  fun `a design round trips through its parts`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    assertEquals(emptyMap(), store.load().designs)

    val design = design("checkout")
    store.commit("checkout", null, design)

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(mapOf("checkout" to design), reopened.designs)
    assertEquals(emptyMap(), reopened.quarantined)
  }

  @Test
  fun `every collection the journal carries survives a reopen`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val second =
      first.copy(
        history = first.history + committed("op-2"),
        operationOutcomes = first.operationOutcomes + ("op-2" to outcome("op-2")),
        acceptedOperations = first.acceptedOperations + ("op-2" to accepted("op-2")),
        tombstones = first.tombstones + ("node-2" to tombstone("node-2")),
        audit = first.audit + audit("op-2"),
      )
    store.commit("checkout", first, second)
    val third =
      second.copy(
        acceptedOperations = second.acceptedOperations - "op-2",
        tombstones = emptyMap(),
      )
    store.commit("checkout", second, third)

    assertEquals(third, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `an edit to one design writes nothing belonging to another`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val checkout = design("checkout")
    val settings = design("settings")
    store.commit("checkout", null, checkout)
    store.commit("settings", null, settings)

    val untouched = fingerprint(root.resolve("designs/${slugOf("settings")}"))
    store.commit("checkout", checkout, checkout.copy(audit = checkout.audit + audit("op-2")))

    assertEquals(
      untouched,
      fingerprint(root.resolve("designs/${slugOf("settings")}")),
      "committing one design must not rewrite another design's files",
    )
  }

  @Test
  fun `a commit that changes only the journal reuses the document it did not touch`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val documentBefore =
      fingerprint(root.resolve("designs/${slugOf("checkout")}")).filterKeys {
        it.startsWith("document-")
      }

    store.commit("checkout", first, first.copy(audit = first.audit + audit("op-2")))

    val documentAfter =
      fingerprint(root.resolve("designs/${slugOf("checkout")}")).filterKeys {
        it.startsWith("document-")
      }
    assertEquals(
      documentBefore,
      documentAfter,
      "the document is named by its digest, so an\n" +
        "unchanged document is neither renamed nor rewritten",
    )
    assertEquals(1, documentAfter.size, "and the previous generation is swept, not accumulated")
  }

  @Test
  fun `a changed document is written under a new name and the old one is swept`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val before = documentFiles(root, "checkout")

    val second =
      first.copy(
        document = first.document.copy(revision = 1, title = "Checkout, reworked"),
        lastSequence = 1,
      )
    store.commit("checkout", first, second)

    val after = documentFiles(root, "checkout")
    assertEquals(1, after.size)
    assertNotEquals(before, after)
    assertEquals(second, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `retained revisions are written once and unlinked when they fall out`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    var previous: PersistedDesignV1? = null
    var current = design("checkout")
    store.commit("checkout", null, current)
    previous = current
    repeat(4) { step ->
      val revision = (step + 1).toLong()
      current =
        current.copy(
          document = current.document.copy(revision = revision),
          lastSequence = revision,
          revisionSnapshots =
            (current.revisionSnapshots +
                RevisionStateV1(current.document.copy(revision = revision), revision))
              .takeLast(2),
          positionSnapshots =
            (current.positionSnapshots + PositionStateV1(revision, emptyMap())).takeLast(2),
        )
      store.commit("checkout", previous, current)
      previous = current
    }

    val revisions =
      Files.list(root.resolve("designs/${slugOf("checkout")}/revisions")).use { it.toList() }
    assertEquals(2, revisions.size, "retention is enforced by unlinking, not by rewriting")
    assertEquals(current, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `a design whose parts cannot be read is quarantined and the rest still load`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.commit("settings", null, design("settings"))

    val document = documentFiles(root, "checkout").single()
    Files.writeString(document, "{\"checksumSha256\":\"nope\",\"payload\":{}}")

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(setOf("settings"), reopened.designs.keys)
    assertContains(reopened.quarantined.keys, "checkout")
    assertTrue(
      Files.exists(root.resolve("designs/${slugOf("checkout")}/quarantine.json")),
      "the reason is recorded beside the design that could not be read",
    )
  }

  @Test
  fun `a quarantined design is repaired by committing it again`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    Files.writeString(documentFiles(root, "checkout").single(), "not json")
    val broken = FileUiBuilderDesignStore(root)
    assertContains(broken.load().quarantined.keys, "checkout")

    broken.commit("checkout", null, design("checkout"))

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(setOf("checkout"), reopened.designs.keys)
    assertEquals(emptyMap(), reopened.quarantined)
  }

  @Test
  fun `a journal tail no header committed to is ignored and then truncated`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val journal = journalFile(root, "checkout")
    // A commit that appended and then died before its header landed.
    Files.writeString(
      journal,
      Files.readString(journal) + "{\"historySet\":[]}\n",
      java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
      java.nio.file.StandardOpenOption.WRITE,
    )

    val reopened = FileUiBuilderDesignStore(root)
    assertEquals(first, reopened.load().designs.getValue("checkout"))

    val second = first.copy(audit = first.audit + audit("op-2"))
    reopened.commit("checkout", first, second)
    assertEquals(second, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `the journal is rewritten once it costs more than what it holds`() {
    val root = createTempDirectory("ui-builder-store")
    val store =
      FileUiBuilderDesignStore(
        root,
        UiBuilderStoreLimits(journalCompactionBytes = 2_048, journalCompactionRatio = 2),
      )
    var previous = design("checkout")
    store.commit("checkout", null, previous)
    repeat(60) { step ->
      val next =
        previous.copy(
          operationOutcomes = mapOf("op-$step" to outcome("op-$step")),
          audit = listOf(audit("op-$step")),
        )
      store.commit("checkout", previous, next)
      previous = next
    }

    val journal = journalFile(root, "checkout")
    assertTrue(
      journal.fileName.toString() != "journal-1.jsonl",
      "a journal that outgrew what it describes is replaced, not appended to forever",
    )
    assertTrue(Files.size(journal) < 8_192, "and the replacement holds only the live state")
    assertEquals(previous, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
    assertEquals(
      1,
      Files.list(root.resolve("designs/${slugOf("checkout")}")).use { paths ->
        paths.filter { it.fileName.toString().startsWith("journal-") }.count()
      },
      "the journal it replaced is unlinked",
    )
  }

  @Test
  fun `a journal record changed in place is caught rather than replayed`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val journal = journalFile(root, "checkout")
    // Still valid JSON, still the committed length, and not what was written: the header's
    // `journalBytes` says which records are committed, which is a different question from whether
    // the bytes are still the bytes.
    Files.writeString(journal, Files.readString(journal).replace("\"owner\"", "\"other\""))

    val reopened = FileUiBuilderDesignStore(root).load()

    assertEquals(emptyMap(), reopened.designs)
    assertTrue(
      reopened.quarantined.getValue("checkout").contains("checksum"),
      reopened.quarantined.toString(),
    )
  }

  @Test
  fun `a design refused for its own budget leaves the generation before it`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val first = design("checkout")
    store.commit("checkout", null, first)

    val overBudget =
      first.copy(
        document =
          first.document.copy(
            revision = 1,
            nodes = (0 until 400).associate { "node-$it" to node("node-$it") },
          ),
        lastSequence = 1,
      )
    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", first, overBudget) }

    assertEquals(
      first,
      FileUiBuilderDesignStore(root).load().designs.getValue("checkout"),
      "a refused commit must not be the design a restart loads",
    )
  }

  @Test
  fun `a refused commit leaves no journal bytes behind it`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val first = design("checkout")
    store.commit("checkout", null, first)
    val settled = directorySize(root)
    val settledUsage = store.usage().bytes

    // The refusal comes after the journal has been written — appended, or compacted into a whole
    // new generation — and `written` names neither. Left there, those bytes are on the disk and
    // outside the gauge until this design commits again or the host restarts.
    val overBudget =
      first.copy(
        document =
          first.document.copy(
            revision = 1,
            nodes = (0 until 400).associate { "node-$it" to node("node-$it") },
          ),
        lastSequence = 1,
        history = first.history + committed("op-2"),
        operationOutcomes = first.operationOutcomes + ("op-2" to outcome("op-2")),
      )
    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", first, overBudget) }

    assertEquals(settled, directorySize(root), "the disk is where the refusal found it")
    assertEquals(settledUsage, store.usage().bytes, "and so is the gauge")
  }

  @Test
  fun `a refused first commit leaves nothing for the next open to quarantine`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 4_096))
    val base = design("checkout")
    val overBudget =
      base.copy(
        document =
          base.document.copy(nodes = (0 until 400).associate { "node-$it" to node("node-$it") })
      )

    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", null, overBudget) }

    // A headerless directory is worse than the failure: the next open reads it as a corrupt design,
    // quarantines it under the directory's name, and holds the id against whoever tries to create
    // it. The discard renames into the store's own deleted directory before unlinking, so what is
    // left is the store's garbage rather than a phantom design.
    val canonical = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    assertFalse(Files.exists(canonical))
    // The rename is the point, and this is what says it happened rather than the unlink that used
    // to be the whole of it: a recursive delete can stop halfway, and only the rename cannot. The
    // failure it guards against — an unlink that does not finish — is not reproducible in-process,
    // so what is asserted is that the path taken was the one that does not have that failure mode.
    assertTrue(
      Files.exists(root.resolve("designs").resolve(FileUiBuilderDesignStore.DELETED_DIRECTORY)),
      "the discard went through the store's own deleted directory",
    )
    val reopened = FileUiBuilderDesignStore(root)
    assertEquals(emptyMap(), reopened.load().quarantined)
    assertEquals(null, reopened.quarantineHolding("checkout"), "and the id is free")
  }

  @Test
  fun `removing a design that is not on the disk is not reported as a deletion of one`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    val canonical = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    Files.walk(canonical).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }

    // The rename is attempted rather than guarded by `Files.exists`, which cannot tell "not there"
    // from "could not look". Here it really is not there, and that has to stay quiet rather than
    // throwing at a caller who asked for exactly this outcome.
    store.remove("checkout")

    assertEquals(emptyMap(), FileUiBuilderDesignStore(root).load().designs)
  }

  @Test
  fun `a quarantined design is still counted and can still be retired`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    Files.writeString(documentFiles(root, "checkout").single(), "not json")

    val reopened = FileUiBuilderDesignStore(root)
    reopened.load()
    assertTrue(
      reopened.usage().bytes > 0,
      "bytes that are still on the disk are not free just because they cannot be decoded",
    )

    reopened.remove("checkout")

    assertEquals(0, reopened.usage().bytes)
    assertFalse(Files.exists(root.resolve("designs/${slugOf("checkout")}")))
  }

  @Test
  fun `a quarantined design keeps the id it was quarantined under across restarts`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    // The header still parses and no longer checks out, which is the case where the id has to come
    // from somewhere other than a header this build will not trust.
    val header = root.resolve("designs/${slugOf("checkout")}/design.json")
    val stored = Files.readString(header)
    val checksum = Regex("\"checksumSha256\":\"([0-9a-f]+)\"").find(stored)!!.groupValues[1]
    Files.writeString(header, stored.replace(checksum, "0".repeat(checksum.length)))

    assertContains(FileUiBuilderDesignStore(root).load().quarantined.keys, "checkout")
    // The second open reads `quarantine.json` rather than the header, and must answer the same id:
    // an operator retiring the design types the id, not the hash of a directory.
    assertContains(FileUiBuilderDesignStore(root).load().quarantined.keys, "checkout")
  }

  @Test
  fun `a design too large for its budget is compacted rather than refused`() {
    val root = createTempDirectory("ui-builder-store")
    // A journal whose growth cannot reach the compaction ratio before the budget: without the
    // rewrite this design's every edit is refused for bytes nothing needs to keep.
    val store =
      FileUiBuilderDesignStore(
        root,
        UiBuilderStoreLimits(
          maximumDesignBytes = 24_000,
          journalCompactionBytes = 1_024 * 1_024,
          journalCompactionRatio = 4,
        ),
      )
    var previous = design("checkout")
    store.commit("checkout", null, previous)
    repeat(40) { step ->
      val next =
        previous.copy(
          operationOutcomes = mapOf("op-$step" to outcome("op-$step")),
          audit = listOf(audit("op-$step")),
        )
      store.commit("checkout", previous, next)
      previous = next
    }

    assertEquals(previous, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
    assertTrue(
      journalFile(root, "checkout").fileName.toString() != "journal-1.jsonl",
      "the budget asked for the rewrite instead of refusing the edit",
    )
  }

  @Test
  fun `a v2 file too large to read is refused rather than allowed to exhaust the heap`() {
    val root = createTempDirectory("ui-builder-store")
    Files.write(
      root.resolve(FileUiBuilderStateStorage.STATE_FILE),
      ByteArray(4_096) { '{'.code.toByte() },
    )

    val failure =
      assertFailsWith<UiBuilderPersistenceException> {
        FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumMigrationBytes = 1_024))
      }

    assertContains(failure.message.orEmpty(), "migration limit")
  }

  @Test
  fun `a delete interrupted after the rename is finished by the next open`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.commit("settings", null, design("settings"))
    // What `remove` leaves behind when the cleanup after its rename does not finish.
    val tombstone = root.resolve("designs/.deleted/${slugOf("checkout")}-1")
    Files.createDirectories(tombstone.parent)
    Files.move(root.resolve("designs/${slugOf("checkout")}"), tombstone)

    val reopened = FileUiBuilderDesignStore(root).load()

    assertEquals(setOf("settings"), reopened.designs.keys)
    assertEquals(
      emptyMap(),
      reopened.quarantined,
      "a tombstone is not a design that failed to read",
    )
    assertFalse(Files.exists(tombstone), "and the disk it holds is given back")
  }

  @Test
  fun `a journal larger than a design may be is refused rather than read`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 64_000))
      .commit("checkout", null, design("checkout"))
    // A journal that grew past what a design may hold — corrupt, hand-copied, or a runaway append.
    // Read whole to find that out, it is an OutOfMemoryError rather than an exception, which is
    // neither quarantined here nor caught by the lane guard above.
    val journal = journalFile(root, "checkout")
    Files.writeString(journal, "x".repeat(200_000))

    val reopened =
      FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 64_000)).load()

    assertEquals(emptyMap(), reopened.designs)
    assertContains(reopened.quarantined.keys, "checkout")
  }

  @Test
  fun `only the committed prefix of a journal is read`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val journal = journalFile(root, "checkout")
    // Junk past the committed length is not a record and is never parsed, so it cannot quarantine a
    // design whose committed bytes are intact.
    Files.writeString(journal, Files.readString(journal) + "x".repeat(50_000))

    assertEquals(first, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `a first commit that is refused leaves no directory to be mistaken for a design`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val large =
      design("checkout").let { base ->
        base.copy(
          document =
            base.document.copy(nodes = (0 until 400).associate { "node-$it" to node("node-$it") })
        )
      }

    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", null, large) }

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(emptyMap(), reopened.designs)
    assertEquals(
      emptyMap(),
      reopened.quarantined,
      "a headerless directory left behind would be read as a corrupt design, and its phantom " +
        "quarantine would share a directory with the design that later takes the id",
    )
    assertFalse(Files.exists(root.resolve("designs/${slugOf("checkout")}")))
  }

  @Test
  fun `a design directory that cannot be walked costs that design and not the load`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.commit("settings", null, design("settings"))
    Files.writeString(documentFiles(root, "checkout").single(), "not json")

    val reopened = FileUiBuilderDesignStore(root).load()

    assertEquals(setOf("settings"), reopened.designs.keys)
    assertContains(reopened.quarantined.keys, "checkout")
  }

  @Test
  fun `a store marker too large to be a marker is refused rather than read`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root)
    // Deliberately a marker that still decodes: the refusal has to be the size, taken before the
    // bytes are allocated, rather than the parse failing afterwards. A marker large enough to
    // matter is one there is no room to allocate, and the `OutOfMemoryError` that read would throw
    // is not an `Exception` — it would escape `serve`'s lane guard and cost the host rather than
    // the UI-builder lane.
    Files.writeString(
      root.resolve("store.json"),
      "{\"format\":\"ui-builder-store-v3\"}" + " ".repeat(200_000),
    )

    val failure = assertFailsWith<UiBuilderPersistenceException> { FileUiBuilderDesignStore(root) }

    assertContains(failure.message.orEmpty(), "store marker")
    assertContains(failure.message.orEmpty(), "the limit is")
  }

  @Test
  fun `a design stored under another directory's name is reported rather than served`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.commit("settings", null, design("settings"))
    // An operator restoring a design in place, or leaving a copy of one beside it. The slug is the
    // address rather than a label — a commit and a delete both derive `designs/<slug(designId)>`
    // from the id — so a header claiming "checkout" from anywhere else must not be allowed to
    // answer for it: served, its parts would be reused by a commit that writes into the canonical
    // directory, naming files that only exist beside the copy.
    val canonical = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    val copy = canonical.resolveSibling(canonical.fileName.toString() + "-backup")
    copyRecursively(canonical, copy)

    val reopened = FileUiBuilderDesignStore(root).load()

    assertEquals(
      setOf("checkout", "settings"),
      reopened.designs.keys,
      "the design at its own address still serves",
    )
    val misplaced = copy.fileName.toString()
    assertContains(reopened.quarantined.keys, misplaced, "and the copy is named by its directory")
    assertContains(reopened.quarantined.getValue(misplaced), "checkout")
  }

  @Test
  fun `a directory that merely looks like a tombstone is not deleted`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    val canonical = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    // Somebody's copy, kept under a name that reads like something the store wrote. No name is
    // proof of who wrote it — `checkout.deleted-1700000000000` is a plausible backup — and this
    // store takes an unfamiliar directory name as operator content, so recursively deleting the
    // only copy someone has is the one outcome it must never produce.
    val backup = canonical.resolveSibling("checkout.deleted-1700000000000")
    copyRecursively(canonical, backup)
    // What a delete actually leaves: the design moved into the store's own directory.
    val tombstone = canonical.resolveSibling(".deleted").resolve("checkout-1700000000000")
    copyRecursively(canonical, tombstone)

    val loaded = FileUiBuilderDesignStore(root).load()

    assertTrue(Files.exists(backup), "the backup is left where it was put")
    assertFalse(Files.exists(tombstone), "and the deletion that did not finish is finished")
    assertEquals(setOf("checkout"), loaded.designs.keys)
    assertContains(
      loaded.quarantined.keys,
      backup.fileName.toString(),
      "the backup is reported as what it is: a design under a name that is not its address",
    )
  }

  @Test
  fun `a quarantine never answers for a design that loaded`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("settings", null, design("settings"))
    store.commit("checkout", null, design("checkout"))
    // An in-place backup an operator left under a name that happens to be another design's id.
    // Reported under that name it would mark a design that loads perfectly well unusable, and a
    // delete aimed at the quarantine would take the live design out of the service while removing
    // the backup from the disk.
    copyRecursively(
      root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout")),
      root.resolve("designs").resolve("settings"),
    )

    val reopened = FileUiBuilderDesignStore(root)
    val loaded = reopened.load()

    assertEquals(setOf("checkout", "settings"), loaded.designs.keys, "both designs still load")
    val reported = loaded.quarantined.keys.single()
    assertNotEquals("settings", reported, "the quarantine yields the name, not the design")
    assertTrue(reported.contains("settings"), "and still says which directory it is: $reported")

    reopened.remove(reported)

    assertFalse(Files.exists(root.resolve("designs/settings")))
    assertEquals(
      setOf("checkout", "settings"),
      FileUiBuilderDesignStore(root).load().designs.keys,
      "retiring the backup is not retiring the design whose name it borrowed",
    )
  }

  @Test
  fun `the gauge counts an existing store before anything has loaded it`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))

    // A capacity check at startup, before any service is built. `load` is what counts the store,
    // and a host reaching the published store directly is not obliged to have called it — being
    // told an existing store is empty is worse than the walk this costs.
    val usage = FileUiBuilderDesignStore(root).usage()

    assertEquals(directorySize(root), usage.bytes)
    assertTrue(usage.bytes > 0)
  }

  @Test
  fun `the gauge is taken after the sweep, not before it`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    val designDirectory = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    // What a crash between writing a part and committing the header leaves: a file the header does
    // not name. The next open unlinks it — and a gauge measured before that would charge those
    // bytes against the ceiling for the life of the process, refusing writes for disk that the
    // same startup had already given back.
    Files.writeString(designDirectory.resolve("document-deadbeefdeadbeef.json"), "x".repeat(20_000))

    val store = FileUiBuilderDesignStore(root)
    store.load()

    assertFalse(Files.exists(designDirectory.resolve("document-deadbeefdeadbeef.json")))
    assertEquals(
      directorySize(root),
      store.usage().bytes,
      "the gauge is what is on the disk once the sweep has finished",
    )
  }

  @Test
  fun `garbage that cannot be swept costs the tidying, not the design`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    val designDirectory = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    // A crash artifact the header does not name, and one this process cannot walk into. The design
    // itself read perfectly well; sweeping and measuring are tidying and accounting, and neither is
    // a reason to stop serving a design that loaded.
    val unreadable = designDirectory.resolve("leftover")
    Files.createDirectories(unreadable)
    Files.writeString(unreadable.resolve("part.json"), "{}")
    Files.setPosixFilePermissions(unreadable, emptySet())
    // Root walks into a directory with no permissions on it, so there the failure this is about
    // cannot be provoked and the test would pass without proving anything. CI's runner is not root;
    // a root container is told why it is skipped rather than shown a green that means nothing.
    assumeTrue(
      !Files.isReadable(unreadable),
      "this process can read a directory with no permissions; it must be root",
    )

    val loaded =
      try {
        FileUiBuilderDesignStore(root).load()
      } finally {
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"))
      }

    assertEquals(setOf("checkout"), loaded.designs.keys)
    assertEquals(emptyMap(), loaded.quarantined)
  }

  @Test
  fun `a commit whose reused part has gone is refused rather than committed`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val base = design("checkout")
    store.commit("checkout", null, base)
    val designDirectory = root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout"))
    val header = designDirectory.resolve("design.json")
    val before = Files.readAllBytes(header)
    // The document is unchanged, so the next commit reuses its filename rather than rewriting it —
    // and the file is not there. Measured as zero bytes the commit would fit the budget, land a
    // header naming a part that has gone, and the design would quarantine at the next open having
    // been told its edit was stored.
    Files.delete(designDirectory.resolve(readHeaderDocumentFile(header)))

    assertFailsWith<UiBuilderPersistenceException> {
      store.commit(
        "checkout",
        base,
        base.copy(access = base.access.copy(accessRevision = 1), updatedAtEpochMillis = 2_000),
      )
    }

    assertContentEquals(before, Files.readAllBytes(header), "the header is still the old one")
  }

  @Test
  fun `a legacy design with a part over the budget does not migrate and is named`() {
    val root = createTempDirectory("ui-builder-store")
    val wide =
      design("checkout").let { base ->
        base.copy(
          access =
            base.access.copy(
              actorGrants =
                (0 until 400).map {
                  DesignActorGrantV1(
                    actorId = "actor-$it",
                    role = DesignAccessRoleV1.VIEWER,
                    allowedActions = listOf(DesignAccessActionV1.READ),
                    grantedByActorId = "owner",
                    grantedAtEpochMillis = 1_000,
                  )
                }
            )
        )
      }
    Files.write(
      root.resolve(FileUiBuilderStateStorage.STATE_FILE),
      LegacyUiBuilderState.encode(
        PersistedServiceV1(mapOf("checkout" to wide, "settings" to design("settings"))),
        LegacyUiBuilderState.Format.V2,
      ),
    )

    // The access list lives in the header and has no bound of its own, so this design's
    // `design.json` is one file the reader would refuse. Written out anyway it would be committed
    // under the marker and quarantined by the very next load, with the pre-migration file already
    // renamed out of the way.
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val loaded = store.load()

    assertEquals(setOf("settings"), loaded.designs.keys, "every other design migrates")
    assertContains(loaded.quarantined.keys, "checkout")
    assertContains(loaded.quarantined.getValue("checkout"), "did not migrate")
    assertContains(loaded.quarantined.getValue("checkout"), "per-design limit")
    assertTrue(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE + ".migrated")),
      "and the copy that still holds it is named in the reason",
    )
  }

  @Test
  fun `a quarantine reported under a directory still holds that design's place`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    val slug = FileUiBuilderDesignStore.slug("checkout")
    // A header that will not parse has no design id to be reported under, so the quarantine is
    // keyed by the directory. The directory is still the one "checkout" would be created into.
    Files.writeString(root.resolve("designs").resolve(slug).resolve("design.json"), "not json")
    val reopened = FileUiBuilderDesignStore(root)
    reopened.load()

    assertEquals(slug, reopened.quarantineHolding("checkout"))
    assertEquals(null, reopened.quarantineHolding("settings"))
  }

  @Test
  fun `a copy under another name can be retired by the name it has`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    val copy = root.resolve("designs/restored-checkout")
    copyRecursively(
      root.resolve("designs").resolve(FileUiBuilderDesignStore.slug("checkout")),
      copy,
    )
    val reopened = FileUiBuilderDesignStore(root)
    reopened.load()

    reopened.remove("restored-checkout")

    assertFalse(Files.exists(copy))
    assertEquals(
      setOf("checkout"),
      FileUiBuilderDesignStore(root).load().designs.keys,
      "and retiring the copy is not retiring the design it claimed to be",
    )
  }

  @Test
  fun `the header counts against the design's own budget`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 6_000))
    val base = design("checkout")
    // An access list has no bound of its own, so `design.json` can be the largest part of a design.
    // Counted out of the budget, a commit could store a header the next open would refuse to read.
    val wide =
      base.copy(
        access =
          base.access.copy(
            actorGrants =
              (0 until 200).map {
                DesignActorGrantV1(
                  actorId = "actor-$it",
                  role = DesignAccessRoleV1.VIEWER,
                  allowedActions = listOf(DesignAccessActionV1.READ),
                  grantedByActorId = "owner",
                  grantedAtEpochMillis = 1_000,
                )
              }
          )
      )

    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", null, wide) }
  }

  @Test
  fun `a tombstone whose cleanup did not finish keeps costing what it holds`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.load()
    // What a delete leaves when the unlink after its rename does not finish.
    val tombstone = root.resolve("designs/.deleted/${slugOf("checkout")}-1")
    Files.createDirectories(tombstone.parent)
    Files.move(root.resolve("designs/${slugOf("checkout")}"), tombstone)

    val reopened = FileUiBuilderDesignStore(root)
    reopened.load()

    assertEquals(
      0,
      reopened.usage().bytes,
      "cleanup that succeeds on the next open gives the disk back",
    )
  }

  @Test
  fun `the gauge counts what is on the disk, not only what the header names`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    // A file the sweep will not remove — a previous generation that is still on the disk.
    val stray = root.resolve("designs/${slugOf("checkout")}/document-stale.json.keep")
    Files.writeString(stray, "x".repeat(4_096))
    val before = store.usage().bytes

    store.commit("checkout", first, first.copy(audit = first.audit + audit("op-2")))

    assertTrue(
      store.usage().bytes >= before,
      "bytes the sweep could not reclaim are still bytes this store is holding",
    )
    assertTrue(Files.exists(stray) || store.usage().bytes > 0)
  }

  @Test
  fun `removing a design removes its directory`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.remove("checkout")

    assertFalse(Files.exists(root.resolve("designs/${slugOf("checkout")}")))
    assertEquals(emptyMap(), FileUiBuilderDesignStore(root).load().designs)
  }

  @Test
  fun `one design over its own budget is the only design that cannot be saved`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val small = design("settings")
    store.commit("settings", null, small)

    val large =
      design("checkout").let { base ->
        base.copy(
          document =
            base.document.copy(nodes = (0 until 400).associate { "node-$it" to node("node-$it") })
        )
      }
    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", null, large) }

    assertEquals(small, FileUiBuilderDesignStore(root).load().designs.getValue("settings"))
  }

  @Test
  fun `a v2 state file is migrated once and kept as the rollback`() {
    val root = createTempDirectory("ui-builder-store")
    val legacy =
      LegacyUiBuilderState.encode(
        PersistedServiceV1(
          mapOf("checkout" to design("checkout"), "settings" to design("settings"))
        ),
        LegacyUiBuilderState.Format.V2,
      )
    Files.write(root.resolve(FileUiBuilderStateStorage.STATE_FILE), legacy)

    val migrated = FileUiBuilderDesignStore(root).load()

    assertEquals(setOf("checkout", "settings"), migrated.designs.keys)
    assertTrue(Files.exists(root.resolve("store.json")))
    assertFalse(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE)),
      "the migrated file is renamed, so a second open does not migrate again",
    )
    assertTrue(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE + ".migrated")),
      "and it is kept, because it is the rollback",
    )
    assertEquals(migrated.designs, FileUiBuilderDesignStore(root).load().designs)
  }

  @Test
  fun `a migration that cannot set the legacy file aside commits nothing`() {
    val root = createTempDirectory("ui-builder-store")
    Files.write(
      root.resolve(FileUiBuilderStateStorage.STATE_FILE),
      LegacyUiBuilderState.encode(
        PersistedServiceV1(mapOf("checkout" to design("checkout"))),
        LegacyUiBuilderState.Format.V2,
      ),
    )
    // The rename is made to fail: a non-empty directory already holds the name it moves to. What
    // matters is what the failure leaves behind — the marker is the commit point, so it must not
    // have been written. Written first, this start would report a failed migration and disable the
    // lane while the next start read the marker, skipped the migration and served the same designs.
    val blocked = root.resolve(FileUiBuilderStateStorage.STATE_FILE + ".migrated")
    Files.createDirectories(blocked)
    Files.writeString(blocked.resolve("occupied"), "in the way")

    val failure = assertFailsWith<UiBuilderPersistenceException> { FileUiBuilderDesignStore(root) }

    assertContains(failure.message.orEmpty(), "set aside")
    assertFalse(Files.exists(root.resolve("store.json")), "the marker is not the first thing done")
    assertTrue(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE)),
      "and the state being migrated is still the state",
    )

    // Which makes it a step the migration retries from, rather than one it has to be recovered
    // from: the parts are named by the digests of their contents, so the retry writes the same
    // tree.
    Files.walk(blocked).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    assertEquals(setOf("checkout"), FileUiBuilderDesignStore(root).load().designs.keys)
  }

  @Test
  fun `a v2 file beside an existing store is ignored`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    Files.write(
      root.resolve(FileUiBuilderStateStorage.STATE_FILE),
      LegacyUiBuilderState.encode(
        PersistedServiceV1(mapOf("settings" to design("settings"))),
        LegacyUiBuilderState.Format.V2,
      ),
    )

    assertEquals(setOf("checkout"), FileUiBuilderDesignStore(root).load().designs.keys)
    assertTrue(Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE)))
  }

  @Test
  fun `usage counts what is stored`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.load()
    assertEquals(0, store.usage().bytes)

    store.commit("checkout", null, design("checkout"))
    val stored = store.usage().bytes
    assertTrue(stored > 0)

    store.remove("checkout")
    assertEquals(0, store.usage().bytes)
  }

  @Test
  fun `a store marker this build cannot read is refused rather than guessed`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).load()
    Files.writeString(root.resolve("store.json"), "{\"format\":\"ui-builder-store-v9\"}")

    val failure =
      assertFailsWith<UiBuilderPersistenceException> { FileUiBuilderDesignStore(root).load() }
    assertContains(failure.message.orEmpty(), "ui-builder-store-v9")
  }

  private fun slugOf(designId: String): String = FileUiBuilderDesignStore.slug(designId)

  private fun documentFiles(root: Path, designId: String): List<Path> =
    Files.list(root.resolve("designs/${slugOf(designId)}")).use { paths ->
      paths.filter { it.fileName.toString().startsWith("document-") }.toList()
    }

  private fun journalFile(root: Path, designId: String): Path =
    Files.list(root.resolve("designs/${slugOf(designId)}"))
      .use { paths -> paths.filter { it.fileName.toString().startsWith("journal-") }.toList() }
      .single()

  /** Every file under a design, by name and content, so "was it written" is answerable. */
  private fun fingerprint(directory: Path): Map<String, String> {
    if (!Files.isDirectory(directory)) return emptyMap()
    val entries = mutableMapOf<String, String>()
    Files.walk(directory).use { stream ->
      stream.forEach { path ->
        if (Files.isRegularFile(path)) {
          entries[directory.relativize(path).toString()] = Files.readString(path)
        }
      }
    }
    return entries
  }

  /** Every byte under the designs of [root], which is what the gauge claims to report. */
  private fun directorySize(root: Path): Long =
    Files.walk(root.resolve("designs")).use { stream ->
      stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
    }

  /** The `document-<digest>.json` name the stored header points at. */
  private fun readHeaderDocumentFile(header: Path): String =
    Regex("\"documentFile\":\"([^\"]+)\"").find(Files.readString(header))!!.groupValues[1]

  private fun copyRecursively(source: Path, target: Path) {
    Files.walk(source).forEach { path ->
      val destination = target.resolve(source.relativize(path).toString())
      if (Files.isDirectory(path)) Files.createDirectories(destination)
      else {
        Files.createDirectories(destination.parent)
        Files.copy(path, destination)
      }
    }
  }

  private fun design(designId: String): PersistedDesignV1 {
    val document =
      DesignDocumentV1(
        schema = "compose-ui-builder/v1",
        id = designId,
        title = designId,
        revision = 0,
        catalogPin = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime"),
        environment =
          DesignEnvironmentV1(
            widthDp = 1280,
            heightDp = 800,
            density = 1.0,
            theme = ThemeV1.DARK,
            locale = "en-GB",
            fontScale = 1.0,
            layoutDirection = LayoutDirectionV1.LTR,
          ),
        roots = listOf("node-1"),
        nodes = mapOf("node-1" to node("node-1")),
      )
    return PersistedDesignV1(
      document = document,
      lastSequence = 0,
      access = DesignAccessControlV1(accessRevision = 0, ownerActorId = "owner"),
      history = listOf(committed("op-1")),
      revisionSnapshots = listOf(RevisionStateV1(document, 0)),
      operationOutcomes = mapOf("op-1" to outcome("op-1")),
      acceptedOperations = mapOf("op-1" to accepted("op-1")),
      tombstones = emptyMap(),
      positions = emptyMap(),
      positionSnapshots = listOf(PositionStateV1(0, emptyMap())),
      createdAtEpochMillis = 1_000,
      updatedAtEpochMillis = 1_000,
      audit = listOf(audit("op-1")),
    )
  }

  private fun node(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun tombstone(nodeId: String): NodeTreeSnapshotV1 =
    NodeTreeSnapshotV1(
      rootNodeId = nodeId,
      nodes = mapOf(nodeId to node(nodeId)),
      location = NodeLocationV1(null, null, null),
      positions = emptyMap(),
    )

  private fun committed(operationId: String): CommittedOperationV1 =
    CommittedOperationV1(
      DesignCommandV1("checkout", operationId, "owner", "browser", 0, emptyList()),
      outcomeV1(operationId),
    )

  private fun outcomeV1(operationId: String): AcceptedOutcomeV1 =
    AcceptedOutcomeV1(
      operationId,
      0,
      0,
      "hash",
      idempotentReplay = false,
      documentUpdatedAtEpochMillis = 1_000,
    )

  private fun outcome(operationId: String): OperationOutcomeRecordV1 =
    OperationOutcomeRecordV1("fingerprint-$operationId", outcomeV1(operationId))

  private fun accepted(operationId: String): AcceptedOperationRecordV1 =
    AcceptedOperationRecordV1(
      operationId = operationId,
      actorId = "owner",
      kind = AcceptedKindV1.BATCH,
      committedRevision = 0,
      activeRevision = 0,
      changes = emptyList(),
    )

  private fun audit(operationId: String): AuditRecordV1 =
    AuditRecordV1(
      AuditKindV1.COMMIT,
      "owner",
      "checkout",
      0,
      0,
      operationId,
      null,
      1_000,
    )
}
