package id.primawash.api

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.io.File

data class Operation(
    val method: String,
    val path: String,
)

/**
 * `openapi.yaml` as an executable contract. OpenAPI 3.1 schemas are JSON Schema 2020-12, so response
 * bodies are validated with a real JSON Schema validator against the schema the document declares for
 * that operation and status.
 *
 * Validation is stricter than the document reads: every object schema that lists `properties`
 * without saying otherwise is treated as `additionalProperties: false`. A field the server sends but
 * the document does not mention is contract drift, and fails the build.
 */
object OpenApiContract {
    private val document: JsonNode by lazy {
        val file = File("openapi.yaml")
        require(file.exists()) { "openapi.yaml tidak ditemukan di ${file.absolutePath}" }
        YAMLMapper().readTree(file)
    }

    private val strictComponents: JsonNode by lazy {
        document.get("components").deepCopy().also { closeObjects(it) }
    }

    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val json = JsonMapper()

    /** Every method + path pair under `paths`. */
    fun operations(): Set<Operation> =
        document
            .get("paths")
            .properties()
            .flatMap { (path, item) ->
                item.propertyNames().filter { it in HTTP_METHODS }.map { Operation(it.uppercase(), path) }
            }.toSet()

    /** Throws with every schema violation when [body] does not match the documented response. */
    fun validate(
        operation: Operation,
        status: Int,
        body: String,
    ) {
        val pathItem =
            document.get("paths").get(operation.path)
                ?: error("${operation.path} tidak terdokumentasi di openapi.yaml")
        val op =
            pathItem.get(operation.method.lowercase())
                ?: error("${operation.method} ${operation.path} tidak terdokumentasi")
        val response =
            op.get("responses").get(status.toString())?.let(::resolve)
                ?: error("${operation.method} ${operation.path} tidak mendokumentasikan status $status")
        val schemaNode =
            response.path("content").path("application/json").get("schema")
                ?: error("${operation.method} ${operation.path} $status tidak punya skema JSON")

        val wrapper = (schemaNode.deepCopy() as ObjectNode)
        wrapper.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        wrapper.set("components", strictComponents)
        if (wrapper.has("properties") &&
            !wrapper.has("additionalProperties")
        ) {
            wrapper.put("additionalProperties", false)
        }

        val errors = registry.getSchema(wrapper).validate(json.readTree(body))
        check(errors.isEmpty()) {
            "${operation.method} ${operation.path} $status tidak sesuai openapi.yaml:\n" +
                errors.joinToString("\n") { "  - ${it.instanceLocation}: ${it.message}" } +
                "\nBody: $body"
        }
    }

    private fun resolve(node: JsonNode): JsonNode {
        val ref = node.get("\$ref")?.asString() ?: return node
        return ref
            .removePrefix("#/")
            .split("/")
            .fold(document) { current, segment -> current.get(segment) ?: error("\$ref $ref tidak ditemukan") }
    }

    private fun closeObjects(node: JsonNode) {
        if (node is ObjectNode) {
            if (node.has("properties") && !node.has("additionalProperties")) node.put("additionalProperties", false)
            node.properties().forEach { (_, child) -> closeObjects(child) }
        } else if (node.isArray) {
            node.forEach(::closeObjects)
        }
    }

    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
}
