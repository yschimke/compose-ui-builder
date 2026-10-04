package ee.schimke.composeai.uibuilder.export.production

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves registered project files and their imports, requiring every input to be in the Git
 * index. Local edits to tracked files and staged new files are accepted. There is no directory scan
 * or network resolution. Source-archive manifests are a future integration; the optional JVM build
 * runner uses this loader.
 */
object ProductionProjectFiles {
  fun load(projectRoot: Path, entryPaths: List<String>): ProductionContractResult {
    val root = projectRoot.toRealPath()
    val inputs = mutableListOf<ProductionInput>()
    val issues = mutableListOf<ProductionContractIssue>()
    val visited = mutableSetOf<String>()

    fun visit(path: String) {
      if (!isProductionUidPath(path)) {
        issues +=
          ProductionContractIssue(
            "INVALID_FILE_PATH",
            path,
            message = "expected a project-relative .uid path",
          )
        return
      }
      if (!visited.add(path)) return
      val file = root.resolve(path)
      try {
        val tracked =
          ProcessBuilder(
              "git",
              "--literal-pathspecs",
              "-C",
              root.toString(),
              "ls-files",
              "--error-unmatch",
              "--",
              path,
            )
            .redirectErrorStream(true)
            .start()
        val output = tracked.inputStream.bufferedReader().use { it.readText() }
        if (tracked.waitFor() != 0) {
          issues +=
            ProductionContractIssue(
              "UNTRACKED_INPUT",
              path,
              message = "input must be checked into the project: ${output.trim()}",
            )
          return
        }
        var ancestor: Path? = file
        var symbolicLink = false
        while (ancestor != null && ancestor != root) {
          if (Files.isSymbolicLink(ancestor)) symbolicLink = true
          ancestor = ancestor.parent
        }
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root) || symbolicLink) {
          issues +=
            ProductionContractIssue(
              "INPUT_OUTSIDE_PROJECT",
              path,
              message = "input must be a regular project file, not a symbolic link",
            )
          return
        }
        val decoded = ProductionUidFiles.decode(Files.readString(file))
        inputs += ProductionInput(path, decoded)
        decoded.imports.forEach(::visit)
      } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        issues +=
          ProductionContractIssue(
            "INVALID_INPUT",
            path,
            message = failure.message ?: "could not read production input",
          )
      }
    }

    if (entryPaths.isEmpty()) {
      return ProductionContractResult.Invalid(
        listOf(
          ProductionContractIssue(
            "NO_ENTRY_FILES",
            "",
            message = "register at least one project .uid file",
          )
        )
      )
    }
    entryPaths.forEach(::visit)
    return when (val validated = ProductionContractValidator.validate(inputs)) {
      is ProductionContractResult.Invalid ->
        ProductionContractResult.Invalid(issues + validated.issues)
      is ProductionContractResult.Valid ->
        if (issues.isEmpty()) validated else ProductionContractResult.Invalid(issues)
    }
  }
}
