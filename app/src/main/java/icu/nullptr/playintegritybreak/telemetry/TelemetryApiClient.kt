package icu.nullptr.playintegritybreak.telemetry

import android.os.Build
import icu.nullptr.playintegritybreak.common.TelemetryBatchPayload
import icu.nullptr.playintegritybreak.common.TelemetryEventPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import it.eldavo.pib.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object TelemetryApiClient {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val SCHEMA_VERSION = 1

    @Serializable
    private data class TelemetryClientInfo(
        val appVersionName: String,
        val appVersionCode: Int,
        val sdkInt: Int,
        val device: String,
        val model: String,
    )

    @Serializable
    private data class TelemetryUploadRequest(
        val schemaVersion: Int,
        val batchId: String,
        val sentAtMs: Long,
        val client: TelemetryClientInfo,
        val events: List<TelemetryEventPayload>,
    )

    @Serializable
    private data class TelemetryUploadResponse(
        val accepted: Boolean = false,
        val ackId: String? = null,
        val retryable: Boolean = true,
        val retryAfterMs: Long? = null,
        val error: String? = null,
    )

    data class UploadOutcome(
        val accepted: Boolean,
        val ackId: String? = null,
        val retriable: Boolean,
        val retryAfterMs: Long? = null,
        val errorMessage: String? = null,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun uploadBatch(endpointUrl: String, authToken: String, batch: TelemetryBatchPayload): UploadOutcome {
        val requestPayload = TelemetryUploadRequest(
            schemaVersion = SCHEMA_VERSION,
            batchId = batch.batchId,
            sentAtMs = System.currentTimeMillis(),
            client = TelemetryClientInfo(
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                sdkInt = Build.VERSION.SDK_INT,
                device = Build.DEVICE,
                model = Build.MODEL,
            ),
            events = batch.events,
        )

        val body = json.encodeToString(requestPayload)
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }

        return try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val rawResponse = readResponse(connection)
            val retryAfterHeader = parseRetryAfterMs(connection.getHeaderField("Retry-After"))

            when {
                statusCode in 200..299 -> {
                    if (rawResponse.isBlank()) {
                        UploadOutcome(
                            accepted = true,
                            ackId = "http-$statusCode-${batch.batchId}",
                            retriable = false,
                        )
                    } else {
                        val parsed = runCatching {
                            json.decodeFromString<TelemetryUploadResponse>(rawResponse)
                        }.getOrNull()

                        if (parsed == null) {
                            UploadOutcome(
                                accepted = true,
                                ackId = "http-$statusCode-${batch.batchId}",
                                retriable = false,
                            )
                        } else {
                            UploadOutcome(
                                accepted = parsed.accepted,
                                ackId = parsed.ackId,
                                retriable = parsed.retryable,
                                retryAfterMs = parsed.retryAfterMs ?: retryAfterHeader,
                                errorMessage = parsed.error,
                            )
                        }
                    }
                }

                statusCode == 408 || statusCode == 429 || statusCode >= 500 -> {
                    UploadOutcome(
                        accepted = false,
                        retriable = true,
                        retryAfterMs = retryAfterHeader,
                        errorMessage = "HTTP $statusCode ${rawResponse.take(200)}".trim(),
                    )
                }

                else -> {
                    UploadOutcome(
                        accepted = false,
                        retriable = false,
                        errorMessage = "HTTP $statusCode ${rawResponse.take(200)}".trim(),
                    )
                }
            }
        } catch (ioe: IOException) {
            UploadOutcome(
                accepted = false,
                retriable = true,
                errorMessage = ioe.message ?: "Network I/O error",
            )
        } catch (t: Throwable) {
            UploadOutcome(
                accepted = false,
                retriable = false,
                errorMessage = t.message ?: "Unexpected upload error",
            )
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

    private fun parseRetryAfterMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val seconds = raw.trim().toLongOrNull() ?: return null
        if (seconds <= 0L) return null
        return seconds * 1000L
    }
}
