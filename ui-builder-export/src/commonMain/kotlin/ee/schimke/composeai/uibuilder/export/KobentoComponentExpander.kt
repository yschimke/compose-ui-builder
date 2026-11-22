package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Kobento component calls (`{"Button": {...}}`) expanded into the plain Remote Compose JSON they
 * stand for, from the library's definitions and theme, so a host can compile a Kobento design
 * (Kobento's own `json2rc`) and play it without asking Kobento's server.
 *
 * Kobento's `convert {expanded: true, mode: "expand"}` is the reference this follows: every call is
 * replaced by its definition's body with the call's arguments, the theme's numbers and the call's
 * locals substituted, `$if`, `$each`, `$count`, `$slot` and `$name` resolved, and each theme colour
 * the result reads added to `resources.colors`. Only expand mode is written: pattern mode is a size
 * optimisation, and every player plays the expanded form.
 *
 * What the expander cannot write yet (an icon, a map, a generated body, an animated local, a
 * component set) is refused with its path, never approximated.
 *
 * The definitions and theme are Kobento's (Apache-2.0), published by kobento-catalog as
 * `kobento/definitions.json` and `kobento/theme.json`.
 */
object KobentoComponentExpander {
  /** The library a document's calls are expanded with. */
  class Library(val definitions: Map<String, JsonObject>, val theme: JsonObject) {
    companion object {
      /** kobento-catalog's `kobento/definitions.json` and `kobento/theme.json`. */
      fun parse(definitions: String, theme: String): Library =
        Library(
          definitions =
            Json.parseToJsonElement(definitions)
              .jsonObject
              .getValue("components")
              .jsonObject
              .mapValues { it.value.jsonObject },
          theme = Json.parseToJsonElement(theme).jsonObject,
        )
    }
  }

  sealed interface Result {
    data class Expanded(val document: JsonObject) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  /** The looks Kobento builds a document in: `componentTheme`. */
  val LOOKS: Set<String> = setOf("auto", "light", "dark", "mono-dark", "mono-light")

  fun expand(document: JsonObject, library: Library): Result = Expander(document, library).run()

  /**
   * The ops that declare a name, and the field it is in. Inside a body the name is the call's own:
   * Kobento writes it `<component><n>_<name>`, as it does a local.
   */
  private val DECLARING_OPS: Map<String, String> =
    mapOf(
      "variable" to "name",
      "textSubtext" to "varName",
      "textMeasure" to "name",
      "pathExpression" to "id",
      "textTransform" to "varName",
      "textLookup" to "varName",
      "touchExpression" to "name",
      "textLength" to "name",
    )

  /** Fields that name a declared path without an `@`: renamed with the declaration. */
  private val NAME_FIELDS: Map<String, String> = mapOf("drawPath" to "path")

  private class Expander(val source: JsonObject, val library: Library) {
    val errors = mutableListOf<String>()
    val look: String =
      (source["componentTheme"] as? JsonPrimitive)?.contentOrNull.let {
        when (it) {
          null -> "auto"
          "mono" -> "mono-dark"
          "mono-inverted" -> "mono-light"
          else -> it
        }
      }
    val mono = look.startsWith("mono-")
    val definitions: Map<String, JsonObject> =
      library.definitions +
        ((source["components"] as? JsonObject)?.mapValues { it.value.jsonObject } ?: emptyMap())
    val numbers: Map<String, JsonElement> = buildMap {
      (library.theme["numbers"] as? JsonObject)?.let(::putAll)
      if (mono) {
        (((library.theme["modes"] as? JsonObject)?.get(look) as? JsonObject)?.get("numbers")
            as? JsonObject)
          ?.let(::putAll)
      }
    }
    val instances = mutableMapOf<String, Int>()
    /** Variables the calls declare, in the order they were declared. */
    val declared = LinkedHashMap<String, JsonElement>()
    /** Of [declared], the calls' own (not `vis_*`). */
    val callDeclared = mutableSetOf<String>()

    fun run(): Result {
      if (look !in LOOKS) errors += "componentTheme: $look is not one of ${LOOKS.joinToString()}"
      if (source["componentSet"] != null) {
        errors += "componentSet: only the built-in library is expanded"
      }
      if ((source["componentSets"] as? JsonArray)?.isNotEmpty() == true) {
        errors += "componentSets: only the built-in library is expanded"
      }
      val root =
        source["root"]
          ?.let { expandTree(it, "root").singleOrNull() }
          ?.let(::resolveTheme)
          ?.let(::hoistVisibility)
      if (errors.isNotEmpty()) return Result.Refused(errors.distinct())
      val live = root?.let(::withoutDeadVariables)
      val resources = resources(live)
      val out = LinkedHashMap<String, JsonElement>()
      source.forEach { (key, value) ->
        when (key) {
          "componentTheme",
          "componentMode",
          "componentSet",
          "componentSets",
          "components",
          "resources" -> Unit
          "root" -> {
            if (resources.isNotEmpty()) out["resources"] = JsonObject(resources)
            live?.let { out["root"] = it }
          }
          else -> out[key] = value
        }
      }
      if (resources.isNotEmpty() && "resources" !in out) out["resources"] = JsonObject(resources)
      return Result.Expanded(JsonObject(out))
    }

    /**
     * [root]'s `resources.variables` without those calls declared that nothing reads: a local only
     * a branch not taken would have read. Kobento prunes these, and only these: a tree variable a
     * call read while it expanded stays, read or not. The document's own declarations stay.
     */
    fun withoutDeadVariables(root: JsonElement): JsonElement {
      while (true) {
        val reads = mutableSetOf<String>()
        fun scan(element: JsonElement, declaration: String?) {
          when (element) {
            is JsonObject -> element.values.forEach { scan(it, declaration) }
            is JsonArray -> element.forEach { scan(it, declaration) }
            is JsonPrimitive ->
              if (element.isString) {
                REFERENCE.findAll(element.content).forEach {
                  if (it.groupValues[1] != declaration) reads += it.groupValues[1]
                }
              }
            else -> Unit
          }
        }
        scan(root, null)
        declared.forEach { (name, value) -> scan(value, name) }
        (source["resources"] as? JsonObject)?.let { scan(it, null) }
        val dead = declared.keys.filter { it !in reads && it in callDeclared }
        if (dead.isEmpty()) return root
        dead.forEach(declared::remove)
      }
    }

    /** The document's own `@theme.*` reads (outside any call), written as the theme's numbers. */
    fun resolveTheme(element: JsonElement): JsonElement =
      when (element) {
        is JsonObject -> JsonObject(element.mapValues { resolveTheme(it.value) })
        is JsonArray -> JsonArray(element.map(::resolveTheme))
        is JsonPrimitive -> {
          val text = element.contentOrNull
          if (!element.isString || text == null || "@theme." !in text) element
          else {
            val whole = THEME_REFERENCE.matchEntire(text)
            if (whole != null) numbers[whole.groupValues[1]] ?: element
            else {
              val written =
                THEME_REFERENCE.replace(text) { match ->
                  (numbers[match.groupValues[1]] as? JsonPrimitive)?.let(::numberText)
                    ?: match.value
                }
              Expressions.fold(written) ?: JsonPrimitive(written)
            }
          }
        }
        else -> element
      }

    /**
     * `visibility` reads a declared integer variable, so a literal written in place (a local that
     * folded) is declared as `vis_<value>` and read from there, as Kobento's expander does.
     */
    fun hoistVisibility(element: JsonElement): JsonElement =
      when (element) {
        is JsonObject ->
          JsonObject(
            element.mapValues { (key, value) ->
              if (key == "visibility" && value is JsonPrimitive && !value.isString) {
                val name = "vis_${numberText(value)}"
                if (name !in declared) declared[name] = value
                JsonPrimitive("@$name")
              } else hoistVisibility(value)
            },
          )
        is JsonArray -> JsonArray(element.map(::hoistVisibility))
        else -> element
      }

    /** The document's resources, the calls' variables after its own, and every colour read. */
    fun resources(root: JsonElement?): Map<String, JsonElement> {
      val own = (source["resources"] as? JsonObject) ?: JsonObject(emptyMap())
      val out = LinkedHashMap<String, JsonElement>(own)
      val variables = LinkedHashMap<String, JsonElement>()
      (own["variables"] as? JsonObject)?.let(variables::putAll)
      declared.forEach { (name, value) -> if (name !in variables) variables[name] = value }
      if (variables.isNotEmpty()) out["variables"] = JsonObject(variables)
      val ownColors = (own["colors"] as? JsonObject) ?: JsonObject(emptyMap())
      val read = mutableSetOf<String>()
      fun scan(element: JsonElement?) {
        when (element) {
          is JsonPrimitive ->
            if (element.isString) {
              COLOR_REFERENCE.matchEntire(element.content)?.let { read += it.groupValues[1] }
            }
          is JsonArray -> element.forEach(::scan)
          is JsonObject -> element.values.forEach(::scan)
          else -> Unit
        }
      }
      scan(root)
      scan(JsonObject(variables))
      val palette = palette()
      val colors = LinkedHashMap<String, JsonElement>()
      read.sorted().forEach { name ->
        (ownColors[name] ?: palette[name])?.let { colors[name] = it }
      }
      ownColors.forEach { (name, value) -> if (name !in colors) colors[name] = value }
      if (colors.isNotEmpty()) out["colors"] = JsonObject(colors)
      return out
    }

    /** The theme's colours in this document's look. */
    fun palette(): Map<String, JsonElement> {
      val themed = (library.theme["colors"] as? JsonObject) ?: return emptyMap()
      return when (look) {
        "auto" -> themed
        "light",
        "dark" -> themed.mapValues { (_, color) -> (color as? JsonObject)?.get(look) ?: color }
        else ->
          themed +
            ((((library.theme["modes"] as? JsonObject)?.get(look) as? JsonObject)?.get("colors")
              as? JsonObject) ?: emptyMap())
      }
    }

    // --- the tree -------------------------------------------------------------------------------

    /**
     * [element] with every component call in it expanded. A call may expand to several nodes (a
     * body that is an array), so the result is a list, spliced where it lands in one.
     */
    fun expandTree(element: JsonElement, path: String): List<JsonElement> {
      if (element is JsonObject && "\$if" in element) {
        // A document's own `$if` (in a slot's nodes, say) can test only the look and direction.
        val condition = (element["\$if"] as? JsonPrimitive)?.contentOrNull ?: ""
        val branch = (if (documentCondition(condition, path)) element["then"] else element["else"])
        return when (branch) {
          null -> emptyList()
          is JsonArray -> branch.flatMapIndexed { index, item -> expandTree(item, "$path[$index]") }
          else -> expandTree(branch, path)
        }
      }
      if (element is JsonObject) {
        callOf(element)?.let { (name, arguments) ->
          return expandCall(name, arguments, path)
        }
        return listOf(
          JsonObject(
            element.mapValues { (key, value) ->
              when (value) {
                is JsonArray -> expandArray(value, "$path.$key")
                is JsonObject ->
                  expandTree(value, "$path.$key").let {
                    if (it.size == 1) it.single() else JsonArray(it)
                  }
                else -> value
              }
            },
          ),
        )
      }
      if (element is JsonArray) return listOf(expandArray(element, path))
      return listOf(element)
    }

    fun documentCondition(text: String, path: String): Boolean =
      text.split("||").any { alternative ->
        alternative.split("&&").all { term ->
          val name = term.trim().removePrefix("!").trim()
          val negated = term.trim().startsWith("!")
          val value =
            when {
              name == "layout.rtl" -> false
              name.startsWith("theme.") ->
                (numbers[name.removePrefix("theme.")] as? JsonPrimitive)?.doubleOrNull?.let {
                  it != 0.0
                } ?: false
              else -> {
                errors += "$path: a document's \$if can test only theme.* and layout.rtl, not $name"
                false
              }
            }
          value != negated
        }
      }

    fun expandArray(array: JsonArray, path: String): JsonArray =
      JsonArray(array.flatMapIndexed { index, item -> expandTree(item, "$path[$index]") })

    /** `{"Button": {...}}` -> ("Button", its arguments), when Button is a component. */
    fun callOf(node: JsonObject): Pair<String, JsonObject>? {
      if (node.size != 1) return null
      val (name, arguments) = node.entries.single()
      if (name !in definitions) return null
      return name to ((arguments as? JsonObject) ?: JsonObject(emptyMap()))
    }

    // --- a call ---------------------------------------------------------------------------------

    fun expandCall(name: String, arguments: JsonObject, path: String): List<JsonElement> {
      val definition = definitions.getValue(name)
      val instance = (instances[name] ?: 0) + 1
      instances[name] = instance
      val prefix = name.lowercase() + instance
      val where = "$path.$name"
      val params = (definition["params"] as? JsonObject) ?: JsonObject(emptyMap())
      val slots = (definition["slots"] as? JsonObject) ?: JsonObject(emptyMap())
      arguments.keys
        .filter { it !in params && it != "slots" && it != "children" && it != "componentMode" }
        .forEach { errors += "$where.$it: $name has no param $it" }
      val bound = LinkedHashMap<String, Param>()
      val unboundVariables = LinkedHashMap<String, Pair<String, JsonElement>>()
      params.forEach { (param, specElement) ->
        val spec = specElement.jsonObject
        val type = (spec["type"] as? JsonPrimitive)?.contentOrNull ?: "string"
        val given = arguments[param]
        if (type == "variable") {
          val reference = (given as? JsonPrimitive)?.takeIf { it.isString }?.content
          if (reference != null && reference.startsWith("@")) {
            bound[param] = Param(JsonPrimitive(reference), type, static = false)
          } else {
            // Unbound, or given a number: the call's own variable, starting there, declared when
            // the body reads it.
            val variable = "${prefix}_$param"
            unboundVariables[param] =
              variable to
                ((given as? JsonPrimitive)?.takeUnless { it.isString }
                  ?: spec["initial"]
                  ?: spec["default"]
                  ?: JsonPrimitive(0))
            bound[param] = Param(JsonPrimitive("@$variable"), type, static = false)
          }
          return@forEach
        }
        val value = given ?: spec["default"]
        if (value == null) {
          if ((spec["required"] as? JsonPrimitive)?.booleanOrNull == true) {
            errors += "$where.$param: $name needs $param"
          }
          return@forEach
        }
        val static =
          (spec["static"] as? JsonPrimitive)?.booleanOrNull == true ||
            type in setOf("enum", "bool", "list")
        bound[param] = Param(value, type, static)
      }
      val content = LinkedHashMap<String, List<JsonElement>>()
      (arguments["slots"] as? JsonObject)?.forEach { (slot, nodes) ->
        if (slot !in slots) errors += "$where.slots.$slot: $name has no slot $slot"
        content[slot] = if (nodes is JsonArray) nodes.toList() else listOf(nodes)
      }
      (arguments["children"] as? JsonArray)?.let { children ->
        content["content"] = content["content"].orEmpty() + children
      }
      val body =
        definition["body"]
          ?: return emptyList<JsonElement>().also { errors += "$where: $name has no body" }
      val call =
        Call(
          expander = this,
          prefix = prefix,
          paramNames = params.keys,
          params = bound,
          slots = content,
          where = where,
          pending = (definition["locals"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
      call.unbound.putAll(unboundVariables)
      // Every local is declared, in the order the definition lists them, as Kobento does: a tree
      // variable stays whether the body reads it or not; an unread resource is pruned at the end.
      call.pending.keys.forEach { call.local(it) }
      val written =
        when (val substituted = call.root.substitute(body, "$where.body")) {
          null -> emptyList()
          is Spliced -> substituted.items
          else -> listOfNotNull(substituted.element)
        }
      // The names the body declares itself, as its own ops write them (`rv_@i` is `rv_0`, `rv_1`):
      // not a local's variable, the call's already, nor a name `$name` took from the caller.
      val patterns = declarationPatterns(body)
      val names =
        written
          .flatMap(::declaredNames)
          .filter { name -> patterns.any { it.matches(name) } }
          .filterNot { it.startsWith("${prefix}_") }
          .toSet()
      val renamed = Renaming(prefix, names)
      val nodes = call.withHead(written.map { renamed.apply(it) }).flatMap { call.fillSlots(it) }
      // A call in the body, or in a slot, is expanded with its arguments already written in.
      return nodes.flatMapIndexed { index, node -> expandTree(node, "$where[$index]") }
    }

    /**
     * What the names [body]'s ops declare look like before substitution: a plain name, or one built
     * from `$each` items (`rv_@i` matches `rv_0`). A `$name` directive takes a caller's variable,
     * which is not the call's to rename.
     */
    fun declarationPatterns(body: JsonElement): List<Regex> {
      val patterns = mutableListOf<Regex>()
      fun walk(element: JsonElement) {
        when (element) {
          is JsonObject ->
            element.forEach { (key, value) ->
              val field = DECLARING_OPS[key]
              val written = ((value as? JsonObject)?.get(field ?: "") as? JsonPrimitive)
              if (field != null && written != null && written.isString) {
                val pattern =
                  written.content.split(REFERENCE).joinToString("[A-Za-z0-9_.]*") {
                    Regex.escape(it)
                  }
                patterns += Regex(pattern)
              }
              walk(value)
            }
          is JsonArray -> element.forEach(::walk)
          else -> Unit
        }
      }
      walk(body)
      return patterns
    }

    /** The names [body]'s ops declare (a canvas `variable`, a `textMeasure`), as written. */
    fun declaredNames(body: JsonElement): Set<String> {
      val names = mutableSetOf<String>()
      fun walk(element: JsonElement) {
        when (element) {
          is JsonObject ->
            element.forEach { (key, value) ->
              declaredName(key, value)?.let { names += it }
              walk(value)
            }
          is JsonArray -> element.forEach(::walk)
          else -> Unit
        }
      }
      walk(body)
      return names
    }
  }

  /** The plain name the op [key] declares in [value], if it is one that declares a name. */
  private fun declaredName(key: String, value: JsonElement): String? {
    val field = DECLARING_OPS[key] ?: return null
    return ((value as? JsonObject)?.get(field) as? JsonPrimitive)
      ?.takeIf { it.isString && PLAIN_NAME.matches(it.content) }
      ?.content
  }

  /**
   * A call's body with the names its ops declare written as the call's own, `<prefix>_<name>`: the
   * declaration, every `@name` that reads it, and a path field that names it. The caller's slot
   * content is not in the body yet (see [Call.fillSlots]), so its names stay the caller's.
   */
  private class Renaming(val prefix: String, val names: Set<String>) {
    fun apply(element: JsonElement): JsonElement =
      if (names.isEmpty()) element
      else
        when (element) {
          is JsonObject ->
            JsonObject(
              element.mapValues { (key, value) ->
                val field = DECLARING_OPS[key] ?: NAME_FIELDS[key]
                val renamed =
                  if (field != null && value is JsonObject) {
                    val name = (value[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (name != null && name in names) {
                      JsonObject(value + (field to JsonPrimitive("${prefix}_$name")))
                    } else value
                  } else value
                apply(renamed)
              },
            )
          is JsonArray -> JsonArray(element.map(::apply))
          is JsonPrimitive ->
            if (element.isString && '@' in element.content) {
              JsonPrimitive(
                REFERENCE.replace(element.content) { match ->
                  if (match.groupValues[1] in names) "@${prefix}_${match.groupValues[1]}"
                  else match.value
                },
              )
            } else element
          else -> element
        }
  }

  /**
   * A param's value at a call: the argument, or the definition's default. [static] is whether it
   * folds where it is spliced (Kobento folds `static`, `enum`, `bool` and `list` params, never a
   * value the caller could later bind to state).
   */
  private class Param(val value: JsonElement, val type: String, val static: Boolean)

  /** What `@name` stands for in a call. */
  private sealed interface Local {
    /** A literal number, text or other JSON value, written in place; [static] as for [Param]. */
    data class Literal(val value: JsonElement, val static: Boolean = true) : Local

    /** A variable reference, `@name`. */
    data class Variable(val name: String) : Local

    /** An expression, spliced in parentheses. */
    data class Expression(val text: String) : Local

    /** An each-item field left out: a whole-value use drops the key. */
    data object Missing : Local
  }

  /** What [Scope.substitute] made of a node: one element, or several to splice into a list. */
  private open class Substituted(val element: JsonElement?, val static: Boolean = true)

  private class Spliced(val items: List<JsonElement>) : Substituted(null)

  /** A directive that writes nothing: dropped from its object or list. */
  private object Dropped : Substituted(null)

  /** One call's arguments, slots and locals: a local is declared the first time it is read. */
  private class Call(
    val expander: Expander,
    val prefix: String,
    val paramNames: Set<String>,
    val params: Map<String, Param>,
    val slots: Map<String, List<JsonElement>>,
    val where: String,
    val pending: JsonObject,
  ) {
    val resolved = LinkedHashMap<String, Local>()
    val resolving = mutableSetOf<String>()
    val root = Scope(this, emptyMap())
    val order = pending.keys.withIndex().associate { (index, name) -> name to index }

    /**
     * The locals that need the component to be laid out (its size, an array, an eased value),
     * written as tree `variable`s at the head of the body, in the order the definition lists them.
     */
    val head = mutableListOf<Triple<Int, String, JsonElement>>()
    val treeVariables = mutableSetOf<String>()

    /** Variable params the caller left unbound: the call's own variable, declared when read. */
    val unbound = LinkedHashMap<String, Pair<String, JsonElement>>()

    fun read(param: String) {
      unbound.remove(param)?.let { (variable, initial) ->
        expander.declared[variable] = initial
        expander.callDeclared += variable
      }
    }

    fun local(name: String): Local? {
      resolved[name]?.let {
        return it
      }
      val value = pending[name] ?: return null
      if (!resolving.add(name)) {
        expander.errors += "$where.locals.$name: reads itself"
        return Local.Missing
      }
      val local = declareLocal(name, value)
      resolving.remove(name)
      resolved[name] = local
      return local
    }

    fun declareLocal(name: String, value: JsonElement): Local {
      val variable = "${prefix}_$name"
      val path = "$where.locals.$name"
      return when {
        value is JsonPrimitive && !value.isString -> {
          expander.declared[variable] = value
          expander.callDeclared += variable
          Local.Variable(variable)
        }
        value is JsonObject && (value["named"] as? JsonPrimitive)?.booleanOrNull == true ->
          Local.Variable(variable)
        value is JsonObject && (value["inline"] as? JsonPrimitive)?.booleanOrNull == true ->
          root.substitute(value["value"] ?: JsonNull, path).toLocal()
        value is JsonObject && "anim" in value -> {
          val eased = root.substitute(value["value"] ?: JsonNull, path)
          // Mono has no motion, and a constant has nothing to ease: the local is the value itself.
          val target = eased.toLocal()
          if (expander.mono || target is Local.Literal) return target
          // An eased value is a tree variable at the head of the body. A duration of exactly 1
          // packs to no animation in the reference parser, so Kobento writes 0.999.
          val duration = root.substitute(value["anim"] ?: JsonPrimitive(0), "$path.anim")?.element
          val seconds = (duration as? JsonPrimitive)?.doubleOrNull
          val anim = if (seconds == 1.0) JsonPrimitive(0.999) else duration ?: JsonPrimitive(0)
          head +=
            Triple(
              order.getValue(name),
              variable,
              JsonObject(mapOf("value" to (eased?.element ?: JsonPrimitive(0)), "anim" to anim)),
            )
          treeVariables += variable
          Local.Variable(variable)
        }
        value is JsonObject && value.keys.any { it.startsWith("\$map") } -> {
          expander.errors += "$path: a map value is not expanded yet"
          Local.Missing
        }
        else ->
          when (val local = root.substitute(value, path).toLocal().folded()) {
            is Local.Expression -> {
              if (needsLayout(local.text)) {
                head += Triple(order.getValue(name), variable, JsonPrimitive(local.text))
                treeVariables += variable
              } else {
                expander.declared[variable] = JsonPrimitive(local.text)
                expander.callDeclared += variable
              }
              Local.Variable(variable)
            }
            else -> local
          }
      }
    }

    /** Whether [expression] reads what only a laid-out component has, so it is a tree variable. */
    fun needsLayout(expression: String): Boolean =
      LAYOUT_READS.containsMatchIn(expression) ||
        REFERENCE.findAll(expression).any { it.groupValues[1] in treeVariables }

    /**
     * [nodes] with the tree variables at the head of the top node: a canvas's, or a container's.
     */
    fun withHead(nodes: List<JsonElement>): List<JsonElement> {
      if (head.isEmpty()) return nodes
      val top = nodes.firstOrNull() as? JsonObject ?: return nodes
      val (kind, body) = top.entries.singleOrNull() ?: return nodes
      if (body !is JsonObject) return nodes
      val field = if (kind == "canvas") "commands" else "children"
      // In the order they were declared: a local after the ones it reads.
      val variables = head.map { (_, name, value) ->
        JsonObject(
          mapOf("variable" to JsonObject(mapOf("name" to JsonPrimitive(name), "value" to value))),
        )
      }
      val existing = (body[field] as? JsonArray)?.toList().orEmpty()
      val written = JsonObject(body + (field to JsonArray(variables + existing)))
      return listOf(JsonObject(mapOf(kind to written))) + nodes.drop(1)
    }

    /** [node] with the slot placeholders [Scope.directive] wrote replaced by the caller's nodes. */
    fun fillSlots(node: JsonElement): List<JsonElement> {
      slotOf(node)?.let {
        return slots[it].orEmpty()
      }
      return listOf(
        when (node) {
          is JsonObject ->
            JsonObject(
              node.mapValues { (_, value) ->
                when {
                  value is JsonArray -> JsonArray(value.flatMap(::fillSlots))
                  slotOf(value) != null ->
                    fillSlots(value).let { if (it.size == 1) it.single() else JsonArray(it) }
                  else -> fillSlots(value).single()
                }
              },
            )
          is JsonArray -> JsonArray(node.flatMap(::fillSlots))
          else -> node
        },
      )
    }

    fun slotOf(node: JsonElement): String? =
      ((node as? JsonObject)?.takeIf { it.size == 1 }?.get(SLOT_MARK) as? JsonPrimitive)?.content
  }

  private fun Substituted?.toLocal(): Local {
    val element = this?.element ?: return Local.Missing
    return when {
      element is JsonPrimitive && !element.isString -> Local.Literal(element, static)
      element is JsonPrimitive ->
        when {
          VARIABLE_ONLY.matches(element.content) ->
            Local.Variable(element.content.removePrefix("@"))
          // Text (a label a local chose), not an expression: written as it is.
          !EXPRESSION_LIKE.containsMatchIn(element.content) -> Local.Literal(element, static)
          else -> Local.Expression(element.content)
        }
      else -> Local.Literal(element, static)
    }
  }

  /** A local written as arithmetic on literals alone (`"1 - 0"`) is that number. */
  private fun Local.folded(): Local =
    if (this is Local.Expression) Expressions.fold(text)?.let { Local.Literal(it) } ?: this
    else this

  /** An `$each` item (or one of its fields): static, as the list it came from. */
  private fun JsonElement?.itemLocal(): Local =
    when {
      this == null -> Local.Missing
      this is JsonPrimitive && isString && VARIABLE_ONLY.matches(content) ->
        Local.Variable(content.removePrefix("@"))
      else -> Local.Literal(this)
    }

  /** A place in a call's body: the call, and the `$each` items bound around it. */
  private class Scope(val call: Call, val bindings: Map<String, Local>) {
    val expander
      get() = call.expander

    fun lookup(name: String): Local? = bindings[name] ?: call.local(name)

    fun child(more: Map<String, Local>) = Scope(call, bindings + more)

    /**
     * [element] with this scope's names written in, `$`-directives resolved. Null drops it (an
     * absent optional param, a `$if` with no branch taken); [Spliced] splices into a list.
     */
    fun substitute(element: JsonElement, path: String): Substituted? =
      when (element) {
        is JsonPrimitive ->
          if (element.isString) string(element.content, path) else Substituted(element)
        is JsonArray -> Substituted(JsonArray(items(element, path)))
        is JsonObject ->
          when (val written = directive(element, path)) {
            null -> objectOf(element, path)
            Dropped -> null
            else -> written
          }
        else -> Substituted(element)
      }

    /** [array]'s items written: a dropped one goes, a spliced one adds its items. */
    fun items(array: JsonArray, path: String): List<JsonElement> =
      array.flatMapIndexed { index, item ->
        when (val written = substitute(item, "$path[$index]")) {
          null -> emptyList()
          is Spliced -> written.items
          else ->
            listOfNotNull(
              written.element?.takeUnless {
                // An object whose every key was dropped (`{"onClick": "@onClick"}` with no
                // onClick given) goes with them.
                it is JsonObject && it.isEmpty() && item is JsonObject && item.isNotEmpty()
              },
            )
        }
      }

    fun objectOf(element: JsonObject, path: String): Substituted {
      val out = LinkedHashMap<String, JsonElement>()
      element.forEach { (key, value) ->
        when (val written = substitute(value, "$path.$key")) {
          null,
          Dropped -> Unit
          is Spliced -> out[key] = JsonArray(written.items)
          else ->
            written.element
              // An object every key of which was dropped goes too: `{"semantics":
              // {"contentDescription": "@label"}}` with no label given is nothing.
              ?.takeUnless {
                it is JsonObject && it.isEmpty() && value is JsonObject && value.isNotEmpty()
              }
              ?.let { out[key] = it }
        }
      }
      return Substituted(JsonObject(out))
    }

    /** A `$`-directive's result, [Dropped] when it writes nothing, or null when it is none. */
    fun directive(element: JsonObject, path: String): Substituted? {
      when {
        "\$if" in element -> {
          val condition = (element["\$if"] as? JsonPrimitive)?.contentOrNull ?: ""
          val branch = if (condition(condition)) element["then"] else element["else"]
          // A branch of JSON null writes nothing, as a missing one does.
          val written =
            branch?.takeUnless { it is JsonNull }?.let { substitute(it, path) } ?: return Dropped
          // A branch that is a list splices where the `$if` stands in one.
          return (written.element as? JsonArray)?.let { Spliced(it.toList()) } ?: written
        }
        "\$each" in element -> return each(element, path)
        "\$count" in element -> {
          val over = (element["\$count"] as? JsonPrimitive)?.contentOrNull ?: ""
          return Substituted(JsonPrimitive(itemsOf(over, path)?.size ?: 0))
        }
        "\$slot" in element -> {
          val slot = (element["\$slot"] as? JsonPrimitive)?.contentOrNull ?: ""
          if (call.slots[slot] == null) return Dropped
          // A placeholder, filled with the caller's nodes once the body's names are the call's
          // own: the caller's nodes keep the caller's names.
          return Spliced(listOf(JsonObject(mapOf(SLOT_MARK to JsonPrimitive(slot)))))
        }
        "\$name" in element -> {
          val target = (element["\$name"] as? JsonPrimitive)?.contentOrNull ?: ""
          val name =
            when (val local = lookup(target)) {
              is Local.Variable -> local.name
              else ->
                (call.params[target]?.value?.also { call.read(target) } as? JsonPrimitive)
                  ?.contentOrNull
                  ?.takeIf { VARIABLE_ONLY.matches(it) }
                  ?.removePrefix("@")
            }
          if (name == null) expander.errors += "$path: \$name $target names no variable"
          return Substituted(JsonPrimitive(name ?: target))
        }
      }
      val unknown = element.keys.firstOrNull { it.startsWith("\$") } ?: return null
      expander.errors += "$path: $unknown is not expanded yet"
      return Dropped
    }

    fun each(element: JsonObject, path: String): Substituted {
      val over = (element["\$each"] as? JsonPrimitive)?.contentOrNull ?: ""
      val items = itemsOf(over, path) ?: return Dropped
      val name = (element["as"] as? JsonPrimitive)?.contentOrNull
      val index = (element["index"] as? JsonPrimitive)?.contentOrNull
      val count = (element["count"] as? JsonPrimitive)?.contentOrNull
      val fields = (element["fields"] as? JsonArray)?.map { (it as JsonPrimitive).content }
      val body = element["do"] ?: return Dropped
      val out = mutableListOf<JsonElement>()
      items.forEachIndexed { i, item ->
        val bindings = LinkedHashMap<String, Local>()
        if (name != null) {
          if (fields == null) {
            bindings[name] = item.itemLocal()
          } else {
            fields.forEachIndexed { f, field ->
              val part =
                when (item) {
                  is JsonObject -> item[field]
                  is JsonArray -> item.getOrNull(f)
                  else -> if (f == 0) item else null
                }
              bindings["${name}_$field"] = part.itemLocal()
            }
            if (item is JsonObject) {
              item.keys
                .filter { it !in fields }
                .forEach { expander.errors += "$path[$i].$it: not one of ${fields.joinToString()}" }
            }
          }
        }
        if (index != null) bindings[index] = Local.Literal(JsonPrimitive(i))
        if (count != null) bindings[count] = Local.Literal(JsonPrimitive(items.size))
        when (val written = child(bindings).substitute(body, "$path[$i]")) {
          null -> Unit
          is Spliced -> out += written.items
          // A `do` that is a list writes its items, as a spliced branch does.
          else ->
            when (val element = written.element) {
              is JsonArray -> out.addAll(element)
              null -> Unit
              else -> out.add(element)
            }
        }
      }
      return Spliced(out)
    }

    /** A list param's items, or `count` times for a number. */
    fun itemsOf(name: String, path: String): List<JsonElement>? {
      val value = valueOf(name)
      return when {
        value is JsonArray -> value.toList()
        value is JsonPrimitive && !value.isString ->
          List(value.doubleOrNull?.toInt() ?: 0) { JsonPrimitive(it) }
        value == null -> emptyList()
        else -> {
          expander.errors += "$path: $name is not a list"
          null
        }
      }
    }

    // --- conditions -----------------------------------------------------------------------------

    fun condition(text: String): Boolean =
      text.split("||").any { alternative -> alternative.split("&&").all { term(it.trim()) } }

    fun term(text: String): Boolean {
      COMPARISON.matchEntire(text)?.let { match ->
        val (name, operator, literal) = match.destructured
        // An absent param compares as JavaScript's `String(undefined)`.
        val value = valueOf(name.trim())
        val text = if (value == null) "undefined" else (value as? JsonPrimitive)?.contentOrNull
        val equal = text == literal
        return if (operator == "==") equal else !equal
      }
      if (text.startsWith("!")) return !term(text.removePrefix("!").trim())
      if (text.startsWith("(") && text.endsWith(")")) {
        return condition(text.substring(1, text.length - 1))
      }
      return truthy(text)
    }

    fun truthy(name: String): Boolean {
      if (name == "layout.rtl") return false
      if (name.startsWith("theme.")) {
        val number = expander.numbers[name.removePrefix("theme.")]
        return (number as? JsonPrimitive)?.doubleOrNull?.let { it != 0.0 } ?: false
      }
      if (name in call.slots) return call.slots.getValue(name).isNotEmpty()
      return when (val value = valueOf(name)) {
        null -> false
        is JsonArray -> value.isNotEmpty()
        is JsonObject -> true
        is JsonPrimitive ->
          if (value.isString) value.content.isNotEmpty()
          else value.booleanOrNull ?: value.doubleOrNull?.let { it != 0.0 } ?: false
        else -> false
      }
    }

    /** What [name] is bound to, as a JSON value. */
    fun valueOf(name: String): JsonElement? =
      when (val local = lookup(name)) {
        is Local.Literal -> local.value
        is Local.Variable -> JsonPrimitive("@${local.name}")
        is Local.Expression -> JsonPrimitive(local.text)
        Local.Missing -> null
        null -> call.params[name]?.value?.also { call.read(name) }
      }

    // --- strings --------------------------------------------------------------------------------

    fun string(text: String, path: String): Substituted? {
      val whole = REFERENCE.matchEntire(text)
      if (whole != null) {
        val name = whole.groupValues[1]
        if (name.startsWith("colors.")) return Substituted(JsonPrimitive(text))
        if (name.startsWith("theme.")) {
          return Substituted(theme(name.removePrefix("theme."), path) ?: JsonPrimitive(text))
        }
        when (val local = lookup(name)) {
          is Local.Literal -> return Substituted(local.value, local.static)
          is Local.Variable -> return Substituted(JsonPrimitive("@${local.name}"))
          is Local.Expression -> return Substituted(JsonPrimitive(local.text))
          Local.Missing -> return null
          null -> Unit
        }
        call.params[name]?.let {
          call.read(name)
          // A param whose default is JSON null is left out, as an absent one is.
          if (it.value is JsonNull) return null
          return Substituted(it.value, it.static)
        }
        // A param the caller left out with no default: the key goes.
        if (name in call.paramNames) return null
        return Substituted(JsonPrimitive(text))
      }
      if ('@' !in text) return Substituted(JsonPrimitive(text))
      var spliced = false
      var static = true
      val written =
        REFERENCE.replace(text) { match ->
          val name = match.groupValues[1]
          val replacement =
            when {
              name.startsWith("colors.") -> null
              name.startsWith("theme.") ->
                (theme(name.removePrefix("theme."), path) as? JsonPrimitive)?.let(::numberText)
              else ->
                splice(name)?.let { (text, isStatic) ->
                  if (!isStatic) static = false
                  text
                }
            }
          if (replacement != null) spliced = true
          replacement ?: match.value
        }
      if (!spliced) return Substituted(JsonPrimitive(written))
      return Substituted(Expressions.fold(written) ?: JsonPrimitive(written), static)
    }

    /**
     * [name]'s value spliced into a string, and whether it is static, or null when [name] is not
     * this call's.
     */
    fun splice(name: String): Pair<String, Boolean>? {
      val static: Boolean
      val value: JsonElement =
        when (val local = lookup(name)) {
          is Local.Literal -> {
            // A literal local or `$each` item: text is written as it is, never as an expression.
            if (local.value is JsonPrimitive && local.value.isString) {
              return local.value.content to local.static
            }
            static = local.static
            local.value
          }
          is Local.Variable -> return "@${local.name}" to true
          is Local.Expression -> return "(${local.text})" to true
          Local.Missing -> return "" to true
          null -> {
            val param =
              call.params[name] ?: return if (name in call.paramNames) "" to true else null
            call.read(name)
            static = param.static
            param.value
          }
        }
      val type = call.params[name]?.type
      val text =
        when {
          value is JsonPrimitive && !value.isString ->
            value.booleanOrNull?.let { if (it) "1" else "0" } ?: numberText(value)
          value is JsonPrimitive -> {
            val content = value.content
            when {
              type == "string" || type == "text" -> content
              VARIABLE_ONLY.matches(content) || NUMBER.matches(content) -> content
              !EXPRESSION_LIKE.containsMatchIn(content) -> content
              else -> "($content)"
            }
          }
          else -> value.toString()
        }
      return text to static
    }

    fun theme(name: String, path: String): JsonElement? {
      val number = expander.numbers[name]
      if (number == null) expander.errors += "$path: theme number '$name' is not in the theme"
      return number
    }
  }

  /** The key of the placeholder a `$slot` leaves in a body until the caller's nodes go in. */
  private const val SLOT_MARK = "\u0000slot"

  private val REFERENCE = Regex("""@([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)""")
  private val VARIABLE_ONLY = Regex("""@[A-Za-z_][A-Za-z0-9_.]*""")
  private val THEME_REFERENCE = Regex("""@theme\.([A-Za-z_][A-Za-z0-9_]*)""")
  private val PLAIN_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
  private val COLOR_REFERENCE = Regex("""@colors\.([A-Za-z_][A-Za-z0-9_]*)""")
  private val NUMBER = Regex("""-?\d+(\.\d+)?""")
  private val EXPRESSION_LIKE = Regex("""[-+*/%()@,]""")
  private val LAYOUT_READS = Regex("""\b(componentWidth|componentHeight|array[A-Z][A-Za-z]*)\(""")
  private val COMPARISON = Regex("""([A-Za-z_][A-Za-z0-9_.]*)\s*(==|!=)\s*'([^']*)'""")

  /** A number as Kobento (JavaScript) writes it: no fraction on a whole number. */
  private fun numberText(value: JsonPrimitive): String =
    value.doubleOrNull?.let(Expressions::format) ?: value.content
}
