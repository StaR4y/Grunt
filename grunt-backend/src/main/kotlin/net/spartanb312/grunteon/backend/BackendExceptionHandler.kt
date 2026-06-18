package net.spartanb312.grunteon.backend

import kotlinx.serialization.SerializationException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.core.JacksonException

@RestControllerAdvice
class BackendExceptionHandler {
    @ExceptionHandler(
        IllegalArgumentException::class,
        IllegalStateException::class,
        SerializationException::class,
        JacksonException::class,
        HttpMessageNotReadableException::class,
    )
    fun badRequest(error: Exception): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(ErrorResponse(error.message ?: "Bad request"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(error: NoSuchElementException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(404).body(ErrorResponse(error.message ?: "Not found"))
    }

    @ExceptionHandler(RedisConnectionFailureException::class)
    fun redisUnavailable(error: RedisConnectionFailureException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(503).body(ErrorResponse(error.message ?: "Redis is unavailable"))
    }
}
