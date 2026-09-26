package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor shell declares its import map before anything that loads a module.
 *
 * Chrome fixes a page's import map once it starts resolving module imports, and a `<link
 * rel="modulepreload">` does that as soon as the module arrives. From the network that is after the
 * parser has passed a later map, so a cold load works; from the cache it is before, so
 * `@js-joda/core` fails to resolve and a reloaded editor never starts. Only reloads broke, which is
 * why nothing but the server's performance harness — the one check that reloads — saw it.
 */
class ShellImportMapOrderTest {
  private val shell =
    File("src/wasmJsMain/resources/index.html")
      .let { relative ->
        // Run from the module directory under Gradle; fall back for a repository-root runner.
        if (relative.isFile) relative else File("ui-builder/${relative.path}")
      }
      .readText()

  @Test
  fun importMapPrecedesEveryModuleLoad() {
    val importMap = shell.indexOf("<script type=\"importmap\">")
    assertTrue(importMap >= 0, "the shell has no import map")
    val moduleLoads =
      listOf("rel=\"modulepreload\"", "type=\"module\"").flatMap { marker ->
        Regex(Regex.escape(marker)).findAll(shell).map { it.range.first }.toList()
      }
    assertTrue(moduleLoads.isNotEmpty(), "the shell loads no modules")
    val early = moduleLoads.filter { it < importMap }
    assertTrue(
      early.isEmpty(),
      "module loads before the import map at offsets $early; a cached reload would resolve " +
        "`@js-joda/core` before the map exists",
    )
  }
}
