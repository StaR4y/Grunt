package net.spartanb312.grunteon.backend

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import net.spartanb312.grunteon.obfuscator.process.DeprecatedTransformer
import net.spartanb312.grunteon.obfuscator.process.GlobalConfig
import net.spartanb312.grunteon.obfuscator.process.HiddenTransformer
import net.spartanb312.grunteon.obfuscator.process.ObfConfig
import net.spartanb312.grunteon.obfuscator.process.StableLevel
import net.spartanb312.grunteon.obfuscator.process.Transformer
import net.spartanb312.grunteon.obfuscator.process.TransformerConfig
import net.spartanb312.grunteon.obfuscator.process.TransformerEntry
import net.spartanb312.grunteon.obfuscator.process.TransformerRegistry
import net.spartanb312.grunteon.obfuscator.process.TransformerRegistryEntry
import net.spartanb312.grunteon.obfuscator.process.nativecode.NativePipelineConfig
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class ObfuscatorConfigService(
    private val objectMapper: ObjectMapper,
) {
    private val json = ObfuscatorConfigJson.json

    fun transformers(includeHidden: Boolean = false): List<TransformerSummaryResponse> {
        return registryEntries(includeHidden).map { entry ->
            val transformer = entry.transformerPrototype
            TransformerSummaryResponse(
                id = transformer.engName,
                name = transformer.engName,
                typeName = entry.typeName(),
                category = transformer.category.name,
                categoryDesc = transformer.category.desc,
                description = transformer.descriptionText(),
                owner = entry.owner,
                hidden = transformer.isHiddenTransformer(),
                deprecated = transformer.isDeprecatedTransformer(),
                stability = transformer.stability()?.name,
                stabilityLevel = transformer.stability()?.level,
                creditMultiplier = transformer.baseMultiplier,
                defaultConfig = defaultConfigNode(entry),
            )
        }
    }

    fun defaultConfig(id: String): JsonNode {
        return defaultConfigNode(findEntry(id))
    }

    fun defaultGlobalConfig(): JsonNode {
        return nodeFromElement(json.encodeToJsonElement(GlobalConfig.serializer(), GlobalConfig()))
    }

    fun defaultNativePipelineConfig(): JsonNode {
        return nodeFromElement(json.encodeToJsonElement(NativePipelineConfig.serializer(), NativePipelineConfig()))
    }

    fun template(includeHidden: Boolean = false, enabled: Boolean = false): JsonNode {
        val config = ObfConfig(
            transformers = registryEntries(includeHidden).map { entry ->
                val transformer = entry.transformerPrototype
                TransformerEntry(
                    name = transformer.engName,
                    enabled = enabled,
                    config = entry.createConfig(),
                )
            }
        )
        return configNode(config)
    }

    fun configNode(payload: JsonNode): JsonNode {
        return configNode(buildConfig(payload))
    }

    fun configNode(config: ObfConfig): JsonNode {
        return objectMapper.readTree(ObfuscatorConfigJson.write(config))
    }

    fun buildConfig(payload: JsonNode): ObfConfig {
        require(payload.isObject) { "Obfuscation config request must be a JSON object" }
        return if (payload.hasStructuredTransformers()) {
            buildStructuredConfig(payload)
        } else {
            ObfuscatorConfigJson.read(payload.toString())
        }
    }

    fun validate(payload: JsonNode): ConfigValidationResponse {
        return validate(buildConfig(payload))
    }

    fun validate(config: ObfConfig): ConfigValidationResponse {
        val enabled = config.transformers.withIndex().filter { it.value.enabled }
        val prototypes = enabled.map { (_, transformerEntry) ->
            TransformerRegistry.find(transformerEntry.config)?.transformerPrototype
        }
        val typedPrototypes = prototypes.filterNotNull()
        val errors = mutableListOf<ConfigValidationError>()

        enabled.forEachIndexed { enabledIndex, indexedEntry ->
            val prototype = prototypes[enabledIndex]
            val transformerName = indexedEntry.value.name.ifBlank {
                prototype?.engName ?: indexedEntry.value.config.javaClass.simpleName
            }
            if (prototype == null) {
                errors += ConfigValidationError(
                    index = indexedEntry.index,
                    transformer = transformerName,
                    message = "Unregistered transformer config: ${indexedEntry.value.config.javaClass.name}",
                )
                return@forEachIndexed
            }

            prototype.orderRules.forEach { (rule, message) ->
                if (!rule(typedPrototypes, enabledIndex)) {
                    errors += ConfigValidationError(
                        index = indexedEntry.index,
                        transformer = transformerName,
                        message = message,
                    )
                }
            }
        }

        return ConfigValidationResponse(
            valid = errors.isEmpty(),
            errors = errors,
        )
    }

    private fun buildStructuredConfig(payload: JsonNode): ObfConfig {
        val globalConfig = payload.get("globalConfig")
            ?.takeUnless { it.isNull }
            ?.let { json.decodeFromString<GlobalConfig>(it.toString()) }
            ?: GlobalConfig()
        val nativePipeline = payload.get("nativePipeline")
            ?.takeUnless { it.isNull }
            ?.let { json.decodeFromString<NativePipelineConfig>(it.toString()) }
            ?: NativePipelineConfig()
        val transformers = payload.get("transformers")
            ?.also { require(it.isArray) { "transformers must be a JSON array" } }
            ?.mapIndexed { index, node -> transformerEntry(index, node) }
            ?: emptyList()

        return ObfConfig(
            globalConfig = globalConfig,
            transformers = transformers,
            nativePipeline = nativePipeline,
        )
    }

    private fun transformerEntry(index: Int, node: JsonNode): TransformerEntry {
        require(node.isObject) { "transformers[$index] must be a JSON object" }
        val id = node.textField("id", "typeName", "transformer")
            ?: throw IllegalArgumentException("transformers[$index] must declare id, typeName, or transformer")
        val entry = findEntry(id)
        val transformer = entry.transformerPrototype
        val config = node.get("config")
            ?.takeUnless { it.isNull }
            ?.let { decodeConfig(index, entry, it) }
            ?: entry.createConfig()

        return TransformerEntry(
            name = node.textField("name") ?: transformer.engName,
            enabled = node.booleanField("enabled", true),
            config = config,
        )
    }

    private fun decodeConfig(index: Int, entry: TransformerRegistryEntry, node: JsonNode): TransformerConfig {
        require(node.isObject) { "transformers[$index].config must be a JSON object" }
        val element = json.parseToJsonElement(node.toString()).jsonObject
        element["type"]?.jsonPrimitive?.contentOrNull?.let { type ->
            val serializer = entry.configSerializer()
            val expected = serializer.descriptor.serialName
            require(type == expected || type == entry.typeName()) {
                "transformers[$index].config type $type does not match ${entry.transformerPrototype.engName}"
            }
        }
        return json.decodeFromJsonElement(entry.configSerializer(), element)
    }

    private fun defaultConfigNode(entry: TransformerRegistryEntry): JsonNode {
        val config = entry.createConfig()
        val element = json.encodeToJsonElement(
            TransformerEntry.serializer(),
            TransformerEntry(
                name = entry.transformerPrototype.engName,
                config = config,
            )
        ).jsonObject.getValue("config")
        return nodeFromElement(element)
    }

    private fun registryEntries(includeHidden: Boolean): List<TransformerRegistryEntry> {
        return TransformerRegistry.entries
            .filter { includeHidden || !it.transformerPrototype.isHiddenTransformer() }
            .sortedWith(compareBy<TransformerRegistryEntry> { it.transformerPrototype.category.ordinal }
                .thenBy { it.transformerPrototype.engName })
    }

    private fun findEntry(id: String): TransformerRegistryEntry {
        val matches = TransformerRegistry.entries.filter { entry ->
            val transformer = entry.transformerPrototype
            id == transformer.engName ||
                id == entry.typeName() ||
                id == entry.configClass.java.simpleName
        }
        require(matches.isNotEmpty()) { "Transformer $id was not found" }
        require(matches.size == 1) { "Transformer $id is ambiguous; use typeName instead" }
        return matches.single()
    }

    private fun nodeFromElement(element: JsonElement): JsonNode {
        return objectMapper.readTree(json.encodeToString(element))
    }

    private fun TransformerRegistryEntry.typeName(): String {
        return configClass.java.name
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun TransformerRegistryEntry.configSerializer(): KSerializer<TransformerConfig> {
        return configClass.serializer() as KSerializer<TransformerConfig>
    }

    private fun Transformer<*>.descriptionText(): String {
        return this::class.java
            .getAnnotation(Transformer.Description::class.java)
            ?.enText
            .orEmpty()
    }

    private fun Transformer<*>.stability(): StableLevel? {
        return this::class.java
            .getAnnotation(Transformer.Stability::class.java)
            ?.level
    }

    private fun Transformer<*>.isHiddenTransformer(): Boolean {
        return this::class.java.isAnnotationPresent(HiddenTransformer::class.java)
    }

    private fun Transformer<*>.isDeprecatedTransformer(): Boolean {
        return this::class.java.isAnnotationPresent(DeprecatedTransformer::class.java) ||
            this::class.java.isAnnotationPresent(Deprecated::class.java) ||
            this::class.java.isAnnotationPresent(java.lang.Deprecated::class.java)
    }

    private fun JsonNode.hasStructuredTransformers(): Boolean {
        val nodes = get("transformers") ?: return false
        require(nodes.isArray) { "transformers must be a JSON array" }
        return nodes.any { it.hasAny("id", "typeName", "transformer") }
    }

    private fun JsonNode.textField(vararg names: String): String? {
        names.forEach { name ->
            val value = get(name)
            if (value != null && !value.isNull) {
                require(value.isString) { "$name must be a string" }
                return value.stringValue().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun JsonNode.booleanField(name: String, default: Boolean): Boolean {
        val value = get(name) ?: return default
        if (value.isNull) return default
        require(value.isBoolean) { "$name must be a boolean" }
        return value.booleanValue()
    }

    private fun JsonNode.hasAny(vararg names: String): Boolean {
        return names.any { has(it) }
    }
}
