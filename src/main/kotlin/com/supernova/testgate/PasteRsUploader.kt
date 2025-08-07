package com.supernova.testgate

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

object PasteRsUploader {
    private val client = HttpClient.newHttpClient()

    /**
     * Uploads the given text to paste.rs and returns the URL of the created paste.
     */
    fun upload(content: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://paste.rs"))
            .header("Content-Type", "application/json")  // or text/plain if you prefer
            .POST(HttpRequest.BodyPublishers.ofString(content))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Failed to upload to paste.rs: HTTP ${response.statusCode()}")
        }
        // paste.rs returns the paste URL in the response body
        return response.body().trim()
    }
}
