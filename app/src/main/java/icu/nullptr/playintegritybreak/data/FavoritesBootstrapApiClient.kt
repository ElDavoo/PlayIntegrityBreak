package icu.nullptr.playintegritybreak.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object FavoritesBootstrapApiClient {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val SCHEMA_VERSION = 1

    @Serializable
    private data class FavoritesBootstrapResponse(
        val schemaVersion: Int = SCHEMA_VERSION,
        val favoritePackages: List<String> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun fetchFavoritePackages(endpointUrl: String): List<String> {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val response = readResponse(connection)
                throw IOException("HTTP $statusCode ${response.take(200)}".trim())
            }

            val body = readResponse(connection)
            if (body.isBlank()) return emptyList()

            val payload = json.decodeFromString<FavoritesBootstrapResponse>(body)
            if (payload.schemaVersion != SCHEMA_VERSION) {
                throw IOException("Unsupported schema version: ${payload.schemaVersion}")
            }

            payload.favoritePackages
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: connection.inputStream ?: return ""
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
}