package ee.schimke.composeai.uibuilder.export

/**
 * The typefaces a design's theme host can set, one per group of type-scale roles, and the property
 * each is authored as. The theme host is `m3/surface`, `wear-m3/screen-scaffold`, or a
 * `remote-m3/widget-container-*`.
 *
 * Per group because brand type is usually a display/text pair; unset groups keep the platform face.
 * Values are family names (vendored or Google Fonts, optionally `google:`-prefixed), resolved by
 * the canvas's font registry and written as `GoogleFont` by the generators. One list shared by
 * catalogs, canvas and generators.
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

/**
 * The type role a theme host sets text in when the text names none (default `bodyLarge`, as both
 * `MaterialTheme`s provide). Canvas, catalog runtimes and generated code all apply it as
 * `ProvideTextStyle(typography.<role>)` inside the theme. A role one scale lacks maps to its
 * nearest member ([m3Role], [wearRole]).
 */
object ThemeTextStyle {
  const val PROPERTY: String = "themeTextStyle"

  /** What both themes provide when the host sets nothing. */
  const val DEFAULT: String = "bodyLarge"

  /** Material 3's roles, the values an `m3/surface` offers. */
  val M3_ROLES: List<String> =
    listOf("display", "headline", "title", "body", "label").flatMap { prefix ->
      listOf("Large", "Medium", "Small").map { prefix + it }
    }

  /** Wear's roles, the values a Wear or Remote Material 3 theme host offers. */
  val WEAR_ROLES: List<String> =
    listOf("displayLarge", "displayMedium", "displaySmall") +
      listOf("titleLarge", "titleMedium", "titleSmall") +
      listOf("labelLarge", "labelMedium", "labelSmall") +
      listOf("bodyLarge", "bodyMedium", "bodySmall", "bodyExtraSmall") +
      listOf(
        "numeralExtraLarge",
        "numeralLarge",
        "numeralMedium",
        "numeralSmall",
        "numeralExtraSmall",
      )

  /**
   * The role [read] names on a host, or null when it names none (or names nothing either scale
   * has).
   */
  fun role(read: (property: String) -> String?): String? =
    read(PROPERTY)?.trim()?.takeIf { it in M3_ROLES || it in WEAR_ROLES }

  /** The role [node] names, read the way every emitter reads a string property. */
  fun role(node: UiBuilderNode): String? = role { node.properties[it]?.stringOrNull() }

  /**
   * [role] as a Material 3 role. Wear's numerals are figures set large, so they take the display
   * and headline sizes in order; `bodyExtraSmall` takes `bodySmall`.
   */
  fun m3Role(role: String): String =
    when (role) {
      "numeralExtraLarge" -> "displayLarge"
      "numeralLarge" -> "displayMedium"
      "numeralMedium" -> "displaySmall"
      "numeralSmall" -> "headlineLarge"
      "numeralExtraSmall" -> "headlineMedium"
      "bodyExtraSmall" -> "bodySmall"
      else -> role
    }

  /** [role] as a Wear role. Wear has no headline roles; they take the matching title role. */
  fun wearRole(role: String): String =
    when (role) {
      "headlineLarge" -> "titleLarge"
      "headlineMedium" -> "titleMedium"
      "headlineSmall" -> "titleSmall"
      else -> role
    }

  /** What an agent reads about the property in the catalog. */
  fun notes(wear: Boolean): String =
    "The type role a text with no `style` of its own is set in, for everything this theme host " +
      "contains — and with it that role's size and typeface. Both themes default to " +
      "`$DEFAULT`; choose `${if (wear) "labelMedium" else "labelLarge"}` for a design whose plain " +
      "text is mostly labels, or a display role for one that is mostly figures. Generated code " +
      "provides it as `ProvideTextStyle(MaterialTheme.typography.<role>)` inside the theme."
}
