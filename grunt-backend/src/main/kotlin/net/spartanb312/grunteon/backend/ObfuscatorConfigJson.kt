package net.spartanb312.grunteon.backend

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.spartanb312.grunteon.obfuscator.process.ObfConfig
import net.spartanb312.grunteon.obfuscator.process.TransformerConfig

object ObfuscatorConfigJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        serializersModule = TransformerConfig.serializersModule()
        prettyPrint = true
        encodeDefaults = true
        prettyPrintIndent = "    "
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun read(text: String): ObfConfig {
        return json.decodeFromString(text)
    }

    fun write(config: ObfConfig): String {
        return json.encodeToString(config)
    }
}
