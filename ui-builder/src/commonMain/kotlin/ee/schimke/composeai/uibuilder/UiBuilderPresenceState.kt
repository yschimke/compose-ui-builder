package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.PresenceLeaveV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpsertV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1

internal const val UI_BUILDER_PRESENCE_HEARTBEAT_MILLIS: Long = 10_000
internal const val UI_BUILDER_PRESENCE_EXPIRY_MILLIS: Long = 30_000

data class UiBuilderCollaborator(
  val actorId: String,
  val displayName: String,
  val colorArgbHex: String,
  val selectedNodeIds: List<String>,
)

internal data class UiBuilderPresenceEntry(
  val value: PresenceV1,
  val lastSeenAtMillis: Long,
)

internal data class UiBuilderPresenceState(
  private val entries: Map<String, UiBuilderPresenceEntry> = emptyMap()
) {
  fun replace(values: List<PresenceV1>, nowMillis: Long): UiBuilderPresenceState =
    UiBuilderPresenceState(values.associate { it.actorId to UiBuilderPresenceEntry(it, nowMillis) })

  fun apply(update: PresenceUpdateV1, nowMillis: Long): UiBuilderPresenceState =
    when (update) {
      is PresenceUpsertV1 ->
        copy(
          entries =
            entries +
              (update.presence.actorId to UiBuilderPresenceEntry(update.presence, nowMillis))
        )
      is PresenceLeaveV1 -> copy(entries = entries - update.actorId)
    }

  fun expire(nowMillis: Long): UiBuilderPresenceState =
    copy(
      entries =
        entries.filterValues { nowMillis - it.lastSeenAtMillis < UI_BUILDER_PRESENCE_EXPIRY_MILLIS }
    )

  fun collaborators(localActorId: String): List<UiBuilderCollaborator> =
    entries.values
      .asSequence()
      .map(UiBuilderPresenceEntry::value)
      .filter { it.actorId != localActorId }
      .sortedWith(compareBy(PresenceV1::displayName, PresenceV1::actorId))
      .map {
        UiBuilderCollaborator(
          actorId = it.actorId,
          displayName = it.displayName,
          colorArgbHex = it.colorArgbHex,
          selectedNodeIds = it.selectedNodeIds,
        )
      }
      .toList()
}
