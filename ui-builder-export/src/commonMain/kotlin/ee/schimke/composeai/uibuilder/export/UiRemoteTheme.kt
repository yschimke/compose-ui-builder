package ee.schimke.composeai.uibuilder.export

/**
 * `RemoteMaterialTheme`: re-skins the one subtree it holds. Role overrides with the same names as
 * [WearScreenTheme]'s; exported as `RemoteMaterialTheme(colorScheme =
 * RemoteMaterialTheme.colorScheme.copy(…)) { … }`. Declared here because the embedded `remote-m3`
 * record has no row for it, like [UiTimeText].
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
