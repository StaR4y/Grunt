package net.spartanb312.grunteon.backend

import net.spartanb312.grunteon.obfuscator.process.transformers.optimize.DeadCodeRemove
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class ObfuscatorConfigServiceTest {
    private val objectMapper = ObjectMapper()
    private val service = ObfuscatorConfigService(objectMapper)

    @Test
    fun listsTransformersFromRegistry() {
        val transformer = service.transformers()
            .first { it.name == "DeadCodeRemove" }

        assertEquals("Optimization", transformer.category)
        assertEquals("optimization", transformer.categoryDesc)
        assertTrue(transformer.defaultConfig.get("type").stringValue().contains("DeadCodeRemove.Config"))
        assertTrue(transformer.defaultConfig.get("pop").booleanValue())
    }

    @Test
    fun buildsConfigFromStructuredRequest() {
        val config = service.buildConfig(json("""
            {
                "globalConfig": {
                    "dumpMappings": false
                },
                "nativePipeline": {
                    "enabled": true
                },
                "transformers": [
                    {
                        "id": "DeadCodeRemove",
                        "enabled": true,
                        "config": {
                            "pop": false
                        }
                    }
                ]
            }
        """.trimIndent()))

        val transformer = config.transformers.single()
        val deadCodeRemove = transformer.config as DeadCodeRemove.Config
        val configNode = service.configNode(config)
        val transformerConfigNode = configNode.get("transformers").get(0).get("config")

        assertFalse(config.globalConfig.dumpMappings)
        assertTrue(config.nativePipeline.enabled)
        assertEquals("DeadCodeRemove", transformer.name)
        assertFalse(deadCodeRemove.pop)
        assertTrue(transformerConfigNode.get("type").stringValue().contains("DeadCodeRemove.Config"))
        assertFalse(transformerConfigNode.get("pop").booleanValue())
    }

    @Test
    fun rejectsMismatchedConfigType() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            service.buildConfig(json("""
                {
                    "transformers": [
                        {
                            "id": "DeadCodeRemove",
                            "config": {
                                "type": "MethodRenamer.Config"
                            }
                        }
                    ]
                }
            """.trimIndent()))
        }

        assertTrue(error.message!!.contains("does not match"))
    }

    @Test
    fun validatesTransformerOrderRules() {
        val config = service.buildConfig(json("""
            {
                "transformers": [
                    {
                        "id": "MethodRenamer"
                    },
                    {
                        "id": "DeadCodeRemove"
                    }
                ]
            }
        """.trimIndent()))
        val validation = service.validate(config)

        assertFalse(validation.valid)
        assertTrue(validation.errors.isNotEmpty())
    }

    private fun json(text: String): JsonNode {
        return objectMapper.readTree(text)
    }
}
