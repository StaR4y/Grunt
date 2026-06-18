package net.spartanb312.grunteon.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

@RestController
@RequestMapping("/api/obfuscator")
class ObfuscatorController(
    private val configService: ObfuscatorConfigService,
) {
    @GetMapping("/transformers")
    fun transformers(
        @RequestParam(defaultValue = "false") includeHidden: Boolean,
    ): List<TransformerSummaryResponse> {
        return configService.transformers(includeHidden)
    }

    @GetMapping("/transformers/{id}/default-config")
    fun defaultConfig(@PathVariable id: String): JsonNode {
        return configService.defaultConfig(id)
    }

    @GetMapping("/global-config/default")
    fun defaultGlobalConfig(): JsonNode {
        return configService.defaultGlobalConfig()
    }

    @GetMapping("/native-pipeline/default-config")
    fun defaultNativePipelineConfig(): JsonNode {
        return configService.defaultNativePipelineConfig()
    }

    @GetMapping("/config/template")
    fun template(
        @RequestParam(defaultValue = "false") includeHidden: Boolean,
        @RequestParam(defaultValue = "false") enabled: Boolean,
    ): JsonNode {
        return configService.template(includeHidden, enabled)
    }

    @PostMapping("/config")
    fun config(@RequestBody payload: JsonNode): JsonNode {
        return configService.configNode(payload)
    }

    @PostMapping("/config/validate")
    fun validate(@RequestBody payload: JsonNode): ConfigValidationResponse {
        return configService.validate(payload)
    }
}
