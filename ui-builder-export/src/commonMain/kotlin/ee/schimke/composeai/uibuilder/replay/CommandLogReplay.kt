package ee.schimke.composeai.uibuilder.replay

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1

/**
 * The one replay in this system: a command log brought back onto the design it forked from. Used
 * by the browser's Sync (`LocalDesignSyncBack`, one request per command) and the service's branch
 * merge (`PersistentUiBuilderService`, inside the lock), so the two cannot drift:
 *
 * - The base chain: `base(c₁)` is the fork point and `base(cₖ)` is where `cₖ₋₁` landed — neither
 *   the target head (which would silently win races) nor the fork point throughout (which would
 *   miss nodes earlier commands inserted). See `docs/design/UI_BUILDER_DESIGN_PORTABILITY.md`.
 * - Every command replays as itself, undos and redos included, in log order.
 * - The first refusal stops the run: later commands were authored on top of it.
 * - A per-command report, never a silent result.
 *
 * Atomicity differs: Sync commits per command (resumable by idempotent replay); a merge commits all
 * or nothing. See `docs/design/UI_BUILDER_BRANCHES.md`.
 *
 * [submit] sends one command at one base; `inline` so suspending and lock-holding callers can both
 * use it. A command landing past [maximumRevision] is reported and the run stops with
 * [REVISION_OVERFLOW].
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
