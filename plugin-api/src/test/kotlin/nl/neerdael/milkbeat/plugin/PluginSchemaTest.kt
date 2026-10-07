package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.compileReferencingRoot
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.generateJsonSchema
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.withTitle
import io.github.smiley4.schemakenerator.jsonschema.data.TitleType
import io.github.smiley4.schemakenerator.serialization.SerializationSteps
import io.github.smiley4.schemakenerator.serialization.SerializationSteps.analyzeTypeUsingKotlinxSerialization
import io.github.smiley4.schemakenerator.serialization.SerializationSteps.convertToKotlinxSerializationTypes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File

/**
 * The JSON Schema of plugin API v1, generated from the Kotlin model. The SDK's TypeScript types are
 * generated from the checked-in copy, so this test failing means the SDK no longer matches the host.
 */
class PluginSchemaTest {
    private val pretty = Json { prettyPrint = true }

    @Test
    fun `the checked-in schema matches the Kotlin model`() {
        val schema = pretty.encodeToString(JsonElement.serializer(), pluginApiSchema()) + "\n"
        val file = File(System.getProperty("pluginSchemaFile"))
        if (System.getProperty("updatePluginSchema").toBoolean()) {
            file.parentFile.mkdirs()
            file.writeText(schema)
            return
        }
        assertWithMessage("${file.path} is stale: run ./gradlew :plugin-api:test -PupdatePluginSchema")
            .that(file.takeIf { it.exists() }?.readText())
            .isEqualTo(schema)
    }

    private fun pluginApiSchema(): JsonObject {
        val definitions = sortedMapOf<String, JsonElement>()

        fun reference(serializer: KSerializer<*>): JsonElement? {
            if (serializer.descriptor == Unit.serializer().descriptor) return null
            val compiled =
                SerializationSteps
                    .initial(serializer.descriptor)
                    .analyzeTypeUsingKotlinxSerialization()
                    .generateJsonSchema()
                    .withTitle(TitleType.SIMPLE)
                    .compileReferencingRoot()
            val converted = withoutPrimitiveTitles(compiled.convertToKotlinxSerializationTypes()).jsonObject
            converted["\$defs"]?.jsonObject?.forEach { (name, definition) -> definitions[name] = definition }
            return JsonObject(converted - "\$defs")
        }

        val manifest = reference(PluginManifest.serializer())!!

        fun calls(operations: List<Triple<String, KSerializer<*>, KSerializer<*>>>) =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    operations.forEach { (path, requestSerializer, responseSerializer) ->
                        val request = reference(requestSerializer)
                        val response = reference(responseSerializer)
                        putJsonObject(path) {
                            put("type", "object")
                            putJsonObject("properties") {
                                request?.let { put("request", it) }
                                response?.let { put("response", it) }
                            }
                            putJsonArray("required") {
                                request?.let { add(JsonPrimitive("request")) }
                                response?.let { add(JsonPrimitive("response")) }
                            }
                            put("additionalProperties", false)
                        }
                    }
                }
                putJsonArray("required") { operations.forEach { add(JsonPrimitive(it.first)) } }
                put("additionalProperties", false)
            }
        val operations = calls(PluginOperations.all.map { Triple(it.path, it.request, it.response) })
        val host = calls(HostOperations.all.map { Triple(it.path, it.request, it.response) })
        withRequiredFromDefaults(definitions)
        allRoots()
            .flatMap(::sealedVariants)
            .distinctBy { it.serialName }
            .forEach { variant ->
                val definition = definitions[variant.serialName]?.jsonObject ?: return@forEach
                definitions[variant.serialName] = withDiscriminator(definition, variant)
            }
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", "MilkbeatPluginApi")
            put("description", "Plugin API v$PLUGIN_API_VERSION, generated from the plugin-api module. Do not edit.")
            put("type", "object")
            putJsonObject("properties") {
                put("manifest", manifest)
                put("operations", operations)
                put("host", host)
                put("error", reference(PluginError.serializer())!!)
            }
            putJsonArray("required") {
                add(JsonPrimitive("manifest"))
                add(JsonPrimitive("operations"))
                add(JsonPrimitive("host"))
                add(JsonPrimitive("error"))
            }
            put("\$defs", JsonObject(definitions))
        }
    }

    /** A variant of a sealed type: kotlinx writes it with `"type": "<serialName>"`. */
    private class SealedVariant(
        val parent: String,
        val serialName: String,
        val title: String,
        val hasTypeField: Boolean,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private fun sealedVariants(root: SerialDescriptor): List<SealedVariant> {
        val seen = mutableSetOf<String>()
        val variants = mutableListOf<SealedVariant>()

        fun visit(descriptor: SerialDescriptor) {
            val container = descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP
            if (!container && !seen.add(descriptor.serialName)) return
            if (descriptor.kind == PolymorphicKind.SEALED) {
                val parent = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
                descriptor.getElementDescriptor(1).elementDescriptors.forEach { variant ->
                    variants +=
                        SealedVariant(
                            parent = descriptor.serialName.removeSuffix("?"),
                            serialName = variant.serialName,
                            title = parent + variant.serialName.replaceFirstChar(Char::uppercase),
                            hasTypeField = (0 until variant.elementsCount).any { variant.getElementName(it) == "type" },
                        )
                    visit(variant)
                }
            }
            descriptor.elementDescriptors.forEach(::visit)
        }
        visit(root)
        return variants
    }

    private fun withDiscriminator(
        definition: JsonObject,
        variant: SealedVariant,
    ): JsonObject {
        val properties = definition["properties"]?.jsonObject.orEmpty()
        val required = definition["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        return JsonObject(
            definition +
                mapOf(
                    "title" to JsonPrimitive(variant.title),
                    "properties" to JsonObject(mapOf("type" to buildJsonObject { put("const", variant.serialName) }) + properties),
                    "required" to JsonArray((listOf("type") + required).distinct().map(::JsonPrimitive)),
                ),
        )
    }

    /**
     * Titles become TypeScript type names, so only object and enum schemas and unions keep theirs; a
     * string or a list titled "String" or "ArrayList" would otherwise turn into dozens of aliases, and
     * a map's title into numbered ones. Property names are data, not schemas, and are never touched.
     */
    private fun withoutPrimitiveTitles(element: JsonElement): JsonElement {
        if (element is JsonArray) return JsonArray(element.map(::withoutPrimitiveTitles))
        if (element !is JsonObject) return element
        val isObject = element["type"].let { it is JsonPrimitive && it.content == "object" } && "properties" in element
        val named = isObject || "enum" in element || "anyOf" in element
        return JsonObject(
            element
                .filterKeys { key -> key != "title" || named }
                .mapValues { (key, value) ->
                    if ((key == "properties" || key == "\$defs") && value is JsonObject) {
                        JsonObject(value.mapValues { (_, schema) -> withoutPrimitiveTitles(schema) })
                    } else {
                        withoutPrimitiveTitles(value)
                    }
                },
        )
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `every field of every class is in the schema`() {
        val definitions = pluginApiSchema()["\$defs"]!!.jsonObject
        val missing =
            allDescriptors()
                .filter { it.kind == StructureKind.CLASS }
                .flatMap { descriptor ->
                    val properties = definitions[descriptor.serialName.removeSuffix("?")]?.jsonObject?.get("properties")?.jsonObject
                    (0 until descriptor.elementsCount)
                        .map(descriptor::getElementName)
                        .filter { properties == null || it !in properties }
                        .map { "${descriptor.serialName}.$it" }
                }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `sealed variants have distinct wire names and no field of their own named type`() {
        val variants = allRoots().flatMap(::sealedVariants).distinctBy { it.parent to it.serialName }
        assertThat(
            variants
                .map { it.serialName }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 },
        ).isEmpty()
        val clashing = variants.filter { it.hasTypeField }.map { it.serialName }
        assertThat(clashing).isEmpty()
    }

    private fun allRoots(): List<SerialDescriptor> =
        PluginOperations.all.flatMap { listOf(it.request.descriptor, it.response.descriptor) } +
            HostOperations.all.flatMap { listOf(it.request.descriptor, it.response.descriptor) } +
            PluginManifest.serializer().descriptor +
            PluginError.serializer().descriptor

    @OptIn(ExperimentalSerializationApi::class)
    private fun allDescriptors(): Collection<SerialDescriptor> {
        val seen = linkedMapOf<String, SerialDescriptor>()

        // Every list is named kotlin.collections.ArrayList and every map LinkedHashMap, so those are
        // walked through each time instead of being remembered by name.
        fun visit(descriptor: SerialDescriptor) {
            val container = descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP
            if (!container && seen.put(descriptor.serialName.removeSuffix("?"), descriptor) != null) return
            descriptor.elementDescriptors.forEach(::visit)
        }
        allRoots().forEach(::visit)
        return seen.values
    }

    /**
     * A field is required only when it has no default, so plugins can leave defaults out. The host
     * always writes them, so what a plugin receives still has every field.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun withRequiredFromDefaults(definitions: MutableMap<String, JsonElement>) {
        allDescriptors()
            .filter { it.kind == StructureKind.CLASS }
            .forEach { descriptor ->
                val name = descriptor.serialName.removeSuffix("?")
                val definition = definitions[name]?.jsonObject ?: return@forEach
                val required =
                    (0 until descriptor.elementsCount)
                        .filterNot(descriptor::isElementOptional)
                        .map { JsonPrimitive(descriptor.getElementName(it)) }
                definitions[name] = JsonObject(definition + ("required" to JsonArray(required)))
            }
    }
}
