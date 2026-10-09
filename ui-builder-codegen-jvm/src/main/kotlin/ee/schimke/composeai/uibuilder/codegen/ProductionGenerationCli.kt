package ee.schimke.composeai.uibuilder.codegen

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.production.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Explicit CLI entry point for build tasks; never called by ordinary single-file export. */
object ProductionGenerationCli {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
      "usage: digest <records...> | validate <root> <entries...> | generate <root> <output> --entries <entries...> --records <records...>"
    }
    when (args[0]) {
      "digest" -> println(recordDigest(args.drop(1).map(Path::of)))
      "validate" -> load(Path.of(args[1]), args.drop(2))
      "generate" -> {
        require(args.size > 6 && args[3] == "--entries") {
          "expected explicit --entries and --records"
        }
        val split = args.indexOf("--records")
        require(split > 4 && split < args.lastIndex) {
          "register entries and pinned component records"
        }
        val root = Path.of(args[1]).toRealPath()
        val contract = load(root, args.slice(4 until split))
        val records = args.drop(split + 1).map(Path::of)
        val json = Json { ignoreUnknownKeys = true }
        val decoded = records.map {
          json.decodeFromString(ComponentRecordFile.serializer(), Files.readString(it))
        }
        val merged =
          decoded
            .first()
            .newBuilder()
            .also { b ->
              b.components = decoded.flatMap { it.components }
              b.builderOrphans = decoded.flatMap { it.builderOrphans }
            }
            .build()
        val generated = ProductionComposeGenerator.generate(contract, merged, recordDigest(records))
        write(root, Path.of(args[2]), generated)
      }
      else -> error("unknown generation mode '${args[0]}'")
    }
  }

  private fun load(root: Path, entries: List<String>): ValidatedProductionContract =
    when (val result = ProductionProjectFiles.load(root, entries)) {
      is ProductionContractResult.Valid -> result.contract
      is ProductionContractResult.Invalid ->
        error(
          result.issues.joinToString("\n") {
            "${it.file}:${it.nodeId ?: it.declaration ?: ""} [${it.code}] ${it.message}"
          }
        )
    }

  /** Hash length-prefixed contents in build-declared order, excluding machine-specific paths. */
  fun recordDigest(records: List<Path>): String {
    require(records.isNotEmpty()) { "at least one component record is required" }
    val digest = MessageDigest.getInstance("SHA-256")
    records.forEach { record ->
      val bytes = Files.readAllBytes(record)
      digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
      digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Only owns a dedicated child directory under build, never arbitrary application sources. */
  fun write(root: Path, requested: Path, generated: List<ProductionGeneratedFile>) {
    val output = requested.toAbsolutePath().normalize()
    val build = root.resolve("build").normalize()
    require(output.startsWith(build) && output != build) {
      "generated output must be a dedicated directory beneath project build/"
    }
    var ancestor: Path? = output
    while (ancestor != null && ancestor.startsWith(root)) {
      require(!Files.isSymbolicLink(ancestor)) { "generated output cannot traverse symbolic links" }
      ancestor = ancestor.parent
    }
    Files.createDirectories(output.parent)
    val staging = Files.createTempDirectory(output.parent, ".uid-staging-")
    try {
      generated.forEach { file ->
        val target = staging.resolve(file.path).normalize()
        require(target.startsWith(staging)) { "generated path escapes output" }
        Files.createDirectories(target.parent)
        Files.writeString(target, file.source)
      }
      if (Files.exists(output)) {
        require(Files.isRegularFile(output.resolve(".uid-generated"))) {
          "output directory is not owned by UID generation"
        }
        output.toFile().deleteRecursively().also {
          require(it) { "could not remove stale generated output" }
        }
      }
      Files.writeString(
        staging.resolve(".uid-generated"),
        "compose-ui-builder-production/v1-candidate\n",
      )
      Files.move(staging, output)
    } finally {
      staging.toFile().deleteRecursively()
    }
  }
}
