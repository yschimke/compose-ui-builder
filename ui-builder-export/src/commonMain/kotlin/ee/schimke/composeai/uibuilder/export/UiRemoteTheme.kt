package ee.schimke.composeai.uibuilder.export

/**
 * `RemoteMaterialTheme`: re-skins the one subtree it holds, so a widget can recolour a part of
 * itself — a card, a row of chips — without recolouring each component in it.
 *
 * Role overrides rather than a seed, for the reasons [WearScreenTheme] gives, and the same roles
 * under the same property names: every one exists on `RemoteColorScheme` too, and an author who
 * learned the screen scaffold's theme already knows this one. The exporter writes
 * `RemoteMaterialTheme(colorScheme = RemoteMaterialTheme.colorScheme.copy(…)) { … }`; the canvas
 * draws the child under the copy the scaffold uses.
 *
 * Declared here because the embedded `remote-m3` record has no row for it, the way [UiTimeText] is.
 */
object UiRemoteTheme {
  const val ID: String = "remote-m3/remote-material-theme"

  /** The slot holding the themed subtree: one child, as `MaterialTheme`'s content is used. */
  const val SLOT: String = "children"

  val PROPERTIES: List<UiDrawing.Property> =
    WearScreenTheme.ROLES.map { role ->
      UiDrawing.Property.Color(
        WearScreenTheme.property(role),
        "The `$role` role inside this theme. The enclosing theme's when absent.",
      )
    }
}
