package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The editor shell declares its import map before anything that loads a module. Chrome fixes the
 * import map once module resolution starts, and a cached `<link rel="modulepreload">` triggers that
 * before a later map is parsed, so `@js-joda/core` fails to resolve on reload.
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
