package net.spartanb312.grunteon.backend

import tools.jackson.databind.JsonNode

data class TransformerSummaryResponse(
    val id: String,
    val name: String,
    val typeName: String,
    val category: String,
    val categoryDesc: String,
    val description: String,
    val owner: String,
    val hidden: Boolean,
    val deprecated: Boolean,
    val stability: String?,
    val stabilityLevel: Int?,
    val creditMultiplier: Double,
    val defaultConfig: JsonNode,
)

data class ConfigValidationResponse(
    val valid: Boolean,
    val errors: List<ConfigValidationError> = emptyList(),
)

data class ConfigValidationError(
    val index: Int,
    val transformer: String,
    val message: String,
)
