package com.supernova.testgate

import org.gradle.api.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** I/O seam so uploads can be mocked/disabled. */
internal interface ReportUploader {
    /**
     * Uploads pretty JSON; returns base URL (e.g., "http://paste.rs/xyz") or null on failure.
     * NOTE: Caller is responsible for appending ".json" for pretty display.
     */
    fun uploadPrettyJson(json: String): String?
}

/** Minimal, dependency-free uploader for paste.rs. */
internal class PasteRsUploader(
    private val logger: Logger
) : ReportUploader {

    override fun uploadPrettyJson(json: String): String? {
        return try {
            val client = HttpClient.newHttpClient()
            val req = HttpRequest.newBuilder()
                .uri(URI.create("https://paste.rs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() in 200..299) {
                res.body().trim().ifEmpty { null }
            } else {
                logger.warn("TestGate: paste.rs returned HTTP ${res.statusCode()}")
                null
            }
        } catch (e: Exception) {
            logger.warn("TestGate: paste.rs upload error: ${e.message}")
            null
        }
    }
}
