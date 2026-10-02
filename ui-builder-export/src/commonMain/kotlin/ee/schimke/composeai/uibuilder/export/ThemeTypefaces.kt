package ee.schimke.composeai.uibuilder.export

/**
 * The typefaces a design's theme host can set, one per group of type-scale roles, and the property
 * each is authored as.
 *
 * A theme host is the node everything in a design reads its theme from: `m3/surface` for Material
 * 3, `wear-m3/screen-scaffold` for a Wear screen, a `remote-m3/widget-container-*` for a Remote
 * Material 3 widget. Before these, a Wear or Remote design could not change its face at all, and an
 * agent asked for three branded variants could only change their colours
 * (yschimke/wear-m3-catalog#684).
 *
 * Per group rather than one family, because a brand's type is almost always a pair — a display face
 * for the large roles and a text face for the rest — and some designs go further (a display face
 * used for the display roles alone). A group left unset keeps the platform's own face for those
 * roles.
 *
 * The value is a family name: a vendored family, or any Google Fonts family, optionally spelled
 * with Remote Compose's `google:` prefix. The canvas resolves it through the host's font registry,
 * and the code generators write it as a `GoogleFont`.
 *
 * One list, read by the catalogs that declare the properties, the canvas that draws them and the
 * generators that write them, so none of them can disagree about which roles a group covers.
 */
object ThemeTypefaces {
  /** One group of roles and the theme-host property that sets their family. */
  data class Group(
    /** `display`, `headline`, `title`, `body` or `label`. */
    val name: String,
    /** `themeDisplayTypeface`, … */
    val property: String,
    /** The Material 3 `Typography` roles the group covers. */
    val m3Roles: List<String>,
    /**
     * The Wear `Typography` / `RemoteTypography` roles the group covers; empty for a group Wear's
     * type scale has no roles for (headline).
     */
    val wearRoles: List<String>,
  )

  private fun sizes(prefix: String, vararg suffixes: String) = suffixes.map { prefix + it }

  val GROUPS: List<Group> =
    listOf(
      Group(
        "display",
        "themeDisplayTypeface",
        m3Roles = sizes("display", "Large", "Medium", "Small"),
        // Wear's numerals are the display face's figures: a clock or a big reading set in the
        // display face beside a title in it would otherwise read as two different brands.
        wearRoles =
          sizes("display", "Large", "Medium", "Small") +
            sizes("numeral", "ExtraLarge", "Large", "Medium", "Small", "ExtraSmall"),
      ),
      Group(
        "headline",
        "themeHeadlineTypeface",
        m3Roles = sizes("headline", "Large", "Medium", "Small"),
        wearRoles = emptyList(),
      ),
      Group(
        "title",
        "themeTitleTypeface",
        m3Roles = sizes("title", "Large", "Medium", "Small"),
        wearRoles = sizes("title", "Large", "Medium", "Small"),
      ),
      Group(
        "body",
        "themeBodyTypeface",
        m3Roles = sizes("body", "Large", "Medium", "Small"),
        wearRoles = sizes("body", "Large", "Medium", "Small", "ExtraSmall"),
      ),
      Group(
        "label",
        "themeLabelTypeface",
        m3Roles = sizes("label", "Large", "Medium", "Small"),
        wearRoles = sizes("label", "Large", "Medium", "Small"),
      ),
    )

  /** The groups a Wear or Remote Material 3 theme host offers: every group Wear has roles for. */
  val WEAR_GROUPS: List<Group> = GROUPS.filter { it.wearRoles.isNotEmpty() }

  /** Every typeface property, for the hosts that hide theme properties from the generic list. */
  val PROPERTIES: Set<String> = GROUPS.mapTo(linkedSetOf()) { it.property }

  /**
   * The family each group's property names on a node, by group; groups with no value are absent.
   * [read] is the node's string property by name.
   */
  fun families(read: (property: String) -> String?): Map<Group, String> =
    GROUPS.mapNotNull { group ->
        read(group.property)?.trim()?.takeIf { it.isNotEmpty() }?.let { group to it }
      }
      .toMap()

  /** The family [families] gives each Material 3 role, by role name. */
  fun m3RoleFamilies(families: Map<Group, String>): Map<String, String> =
    families.flatMap { (group, family) -> group.m3Roles.map { it to family } }.toMap()

  /** The family [families] gives each Wear / Remote Material 3 role, by role name. */
  fun wearRoleFamilies(families: Map<Group, String>): Map<String, String> =
    families.flatMap { (group, family) -> group.wearRoles.map { it to family } }.toMap()

  /** A family name without the `google:` prefix and with its whitespace tidied. */
  fun familyName(value: String): String =
    value.trim().removePrefix("google:").trim().replace(Regex("\\s+"), " ")

  /** What an agent reads about one group's property in the catalog. */
  fun notes(group: Group, wear: Boolean): String {
    val roles = if (wear) group.wearRoles else group.m3Roles
    return "The font family for the ${group.name} roles (${roles.joinToString(", ")}) of " +
      "everything this theme host contains. A family name: one of the editor's vendored faces or " +
      "any Google Fonts family (\"Space Grotesk\", \"Michroma\"); a `google:` prefix is " +
      "accepted. Generated code sets it as a `GoogleFont` family on the theme's typography. " +
      "Unset keeps the platform's own face for these roles."
  }

  /** The groups [node] names a family for, read the way every emitter reads a string property. */
  fun families(node: UiBuilderNode): Map<Group, String> = families {
    node.properties[it]?.stringOrNull()
  }
}
