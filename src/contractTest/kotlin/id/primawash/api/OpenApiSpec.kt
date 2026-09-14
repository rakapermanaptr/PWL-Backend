package id.primawash.api

import java.io.File

/**
 * Minimal reader for the parts of `openapi.yaml` the M0 contract tests assert against.
 *
 * Deliberately hand-rolled: adding an OpenAPI parser/validator library is a dependency decision that
 * belongs to M1, when request/response schemas for real endpoints appear. Until then this is enough
 * to catch the drift that matters — a documented path that the server does not serve, or a response
 * field that the document does not mention.
 */
object OpenApiSpec {
    private val text: String by lazy {
        val file = File("openapi.yaml")
        require(file.exists()) { "openapi.yaml tidak ditemukan di ${file.absolutePath}" }
        file.readText()
    }

    /** Top-level keys under `paths:` — the endpoints this document claims to specify. */
    fun documentedPaths(): List<String> {
        val paths = mutableListOf<String>()
        var insidePaths = false
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("paths:") -> insidePaths = true
                insidePaths && line.isNotBlank() && !line.startsWith(" ") -> insidePaths = false
                insidePaths && line.startsWith("  /") && line.trimEnd().endsWith(":") ->
                    paths += line.trim().removeSuffix(":")
            }
        }
        return paths
    }

    /** `required: [a, b]` of a schema under `components.schemas`. */
    fun requiredFields(schema: String): Set<String> {
        val block = schemaBlock(schema)
        val required =
            Regex("""required:\s*\[([^\]]*)]""")
                .find(block)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return required
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Every `enum: [...]` value declared inside a schema, flattened. */
    fun enumValues(schema: String): Set<String> =
        Regex("""enum:\s*\[([^\]]*)]""")
            .findAll(schemaBlock(schema))
            .flatMap { match -> match.groupValues[1].split(",").map { it.trim() } }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun schemaBlock(schema: String): String {
        val start = text.indexOf("    $schema:")
        require(start >= 0) { "Schema $schema tidak ada di openapi.yaml" }
        val rest = text.substring(start + 1)
        val end = Regex("""\n {4}\w""").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }
}
