package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.rcplayer.compose.RcComposeSupportIssue
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocument

/**
 * What stops the player drawing [this]: its support report's issues, less the one that judges only
 * its accessibility metadata. A component that says it is clickable with no click of its own (a
 * button whose format compiles it without one, as one is when first inserted) draws exactly as it
 * would with one; the editor's canvas, palette and Preview take no press on it, so it is no reason
 * to show a placeholder or an error in its place.
 */
internal fun RcDocument.blockingPlayerIssues(): List<RcComposeSupportIssue> =
  composeSupportReport().issues.filterNot {
    it.operation == "CoreSemantics" && it.detail.startsWith(UNDISPATCHED_CLICK)
  }

/** rc-player's `composeSupportReport` detail for clickable semantics with no click on it. */
private const val UNDISPATCHED_CLICK = "clickable semantics requires a ClickModifierOperation"
