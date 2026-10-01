package ee.schimke.composeai.uibuilder.replay

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1

/**
 * The one replay in this system: a command log brought back onto the design it forked from.
 *
 * Two callers, one rule. The browser's **Sync** (`LocalDesignSyncBack`) replays a checkout's log
 * onto the server over the wire, one request per command; the service's **branch merge**
 * (`PersistentUiBuilderService`, `UiBuilderBranchPort`) replays a branch's log onto its parent
 * inside the service lock. What they share is what makes the result explainable, and it lives here
 * so that the two cannot drift:
 *
 * - **The base chain.** `base(c₁)` is the fork point and `base(cₖ)` is the revision `cₖ₋₁` landed
 *   at. Not the target's head — that would make every replayed command newer than the concurrent
 *   edits it races, so it would win silently and the conflict notices would be thrown away. And not
 *   the fork point throughout — that would make the run concurrent with itself, so a move of a node
 *   an earlier command inserted would resolve against a position snapshot where that node does not
 *   exist. `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md` has the long form.
 * - **Every command replays as itself**, undos and redos included, in log order.
 * - **The first refusal stops the run.** A refused command is a decision the reducer will not make
 *   on anybody's behalf (a stale delete, an undo of something since overwritten); every later
 *   command was authored on top of it, so replaying past it would apply edits to a document their
 *   author never saw.
 * - **A per-command report**, never a silent result: what landed where, what it overwrote, where it
 *   stopped and how much is left.
 *
 * What the two callers do *not* share is atomicity, and deliberately: Sync commits each command as
 * it lands, because each one is a separate request and the run is resumable through idempotent
 * replay; a branch merge runs in one critical section, so it commits all of the run or none of it.
 * See `docs/design/UI_BUILDER_BRANCHES.md`.
 *
 * [submit] sends one command at one base and answers what the reducer said. It is `inline` so that
 * a suspending caller (Sync) and a caller holding a lock (the service) can both use it as is.
 * [maximumRevision] is the highest revision the caller can carry as a base; a command that lands
 * past it is reported as landed and the run stops after it with [REVISION_OVERFLOW].
 */
public inline fun <C> replayCommandLog(
  forkRevision: Long,
  log: List<C>,
  maximumRevision: Long = Long.MAX_VALUE,
  submit: (command: C, baseRevision: Long) -> ReplayAnswer,
): CommandLogReplay {
  var base = forkRevision
  val landed = mutableListOf<ReplayedCommand>()
  log.forEachIndexed { index, command ->
    val remaining = log.size - index - 1
    when (val answer = submit(command, base)) {
      is ReplayAnswer.Landed -> {
        landed +=
          ReplayedCommand(
            index = index,
            operationId = answer.operationId,
            baseRevision = base,
            committedRevision = answer.committedRevision,
            idempotentReplay = answer.idempotentReplay,
            conflicts = answer.conflicts,
          )
        if (answer.committedRevision !in 0..maximumRevision) {
          return CommandLogReplay(
            forkRevision,
            landed,
            ReplayStop(
              index = index,
              operationId = answer.operationId,
              baseRevision = base,
              code = REVISION_OVERFLOW,
              message =
                "revision ${answer.committedRevision} is past the highest revision this replay " +
                  "can name",
              remaining = remaining,
            ),
          )
        }
        base = answer.committedRevision
      }
      is ReplayAnswer.Refused ->
        return CommandLogReplay(
          forkRevision,
          landed,
          ReplayStop(
            index = index,
            operationId = answer.operationId,
            baseRevision = base,
            code = answer.code,
            message = answer.message,
            remaining = remaining,
            nodeId = answer.nodeId,
            field = answer.field,
          ),
        )
    }
  }
  return CommandLogReplay(forkRevision, landed, stop = null)
}

/** The code a run stops with when a command lands past `maximumRevision`. */
public const val REVISION_OVERFLOW: String = "REVISION_OVERFLOW"

/** What the reducer said about one replayed command. */
public sealed interface ReplayAnswer {
  public data class Landed(
    val operationId: String,
    val committedRevision: Long,
    /** True when the target had this command already: a resumed run, not news. */
    val idempotentReplay: Boolean,
    /** What this command overwrote — last-writer-wins notices, never refusals. */
    val conflicts: List<CommandConflictV1>,
  ) : ReplayAnswer

  public data class Refused(
    val operationId: String,
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val field: String? = null,
  ) : ReplayAnswer

  public companion object {
    /** A reducer outcome, as the replay reads it. */
    public fun of(outcome: CommandOutcomeV1): ReplayAnswer =
      when (outcome) {
        is AcceptedOutcomeV1 ->
          Landed(
            outcome.operationId,
            outcome.committedRevision,
            outcome.idempotentReplay,
            outcome.conflicts,
          )
        is RejectedOutcomeV1 ->
          Refused(
            outcome.operationId,
            outcome.code.name,
            outcome.message,
            nodeId = outcome.nodeId,
            field = outcome.field,
          )
      }
  }
}

/** One command that landed, at the base the chain gave it. */
public data class ReplayedCommand(
  /** Position in the replayed log. */
  val index: Int,
  val operationId: String,
  val baseRevision: Long,
  val committedRevision: Long,
  val idempotentReplay: Boolean,
  val conflicts: List<CommandConflictV1>,
)

/** The command a run stopped at, and how much of the log comes after it. */
public data class ReplayStop(
  val index: Int,
  val operationId: String,
  val baseRevision: Long,
  val code: String,
  val message: String,
  /** Commands after this one that were never attempted. */
  val remaining: Int,
  val nodeId: String? = null,
  val field: String? = null,
)

/** A whole run: everything that landed, in order, and where it stopped if it did. */
public data class CommandLogReplay(
  val forkRevision: Long,
  val landed: List<ReplayedCommand>,
  val stop: ReplayStop?,
) {
  val complete: Boolean
    get() = stop == null

  val conflicts: List<CommandConflictV1>
    get() = landed.flatMap { it.conflicts }
}
