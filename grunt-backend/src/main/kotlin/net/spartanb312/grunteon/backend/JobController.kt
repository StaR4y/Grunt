package net.spartanb312.grunteon.backend

import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper
import kotlin.io.path.inputStream
import kotlin.io.path.name

@RestController
@RequestMapping("/api/jobs")
class JobController(
    private val jobService: JobService,
    private val configService: ObfuscatorConfigService,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(
        @RequestPart("config") config: MultipartFile,
        @RequestPart("input") input: MultipartFile,
        @RequestPart("libs", required = false) libs: List<MultipartFile>?,
    ): JobSubmitResponse {
        val response = jobService.submit(config, input, libs.orEmpty())
        return JobSubmitResponse(response.jobId, response.status)
    }

    @PostMapping("/configured", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitConfigured(
        @RequestPart("request") request: String,
        @RequestPart("input") input: MultipartFile,
        @RequestPart("libs", required = false) libs: List<MultipartFile>?,
    ): JobSubmitResponse {
        val config = configService.buildConfig(objectMapper.readTree(request))
        val validation = configService.validate(config)
        require(validation.valid) {
            "Invalid obfuscation config: " + validation.errors.joinToString("; ") {
                "[${it.index}] ${it.transformer}: ${it.message}"
            }
        }
        val response = jobService.submit(config, input, libs.orEmpty())
        return JobSubmitResponse(response.jobId, response.status)
    }

    @GetMapping("/{jobId}")
    fun status(@PathVariable jobId: String): JobStatusResponse {
        return jobService.get(jobId)
    }

    @GetMapping("/{jobId}/result")
    fun result(@PathVariable jobId: String): ResponseEntity<InputStreamResource> {
        val result = jobService.resultPath(jobId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(result.name).build().toString(),
            )
            .body(InputStreamResource(result.inputStream()))
    }
}
