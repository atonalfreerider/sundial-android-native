package com.metavirtuoso.sundial.horoscope

import com.metavirtuoso.sundial.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Sends a flagged horoscope to the developer from inside the app, as Google Play's AI-generated
 * content policy requires. Reports go to [BuildConfig.REPORT_ENDPOINT] as a form post, so a
 * Google Form's `formResponse` URL works; [BuildConfig.REPORT_FIELDS] renames the fields
 * (`reason=entry.123,reading=entry.456,...`) to match it.
 */
object ReadingReporter {
    enum class Reason(val label: String) {
        OFFENSIVE("Offensive or hateful"),
        HARMFUL("Harmful or dangerous advice"),
        SEXUAL("Sexual or explicit"),
        MISLEADING("Presented as fact or misleading"),
        OTHER("Something else"),
    }

    data class Report(val reason: Reason, val reading: String, val date: LocalDate)

    val isConfigured: Boolean get() = BuildConfig.REPORT_ENDPOINT.isNotBlank()

    /** Returns true once the endpoint has accepted the report. */
    suspend fun send(report: Report): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        val fields = fieldNames()
        val body = mapOf(
            "reason" to report.reason.label,
            "reading" to report.reading,
            "date" to report.date.toString(),
            "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        ).entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(fields[key] ?: key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        runCatching {
            val connection = URL(BuildConfig.REPORT_ENDPOINT).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    internal fun fieldNames(spec: String = BuildConfig.REPORT_FIELDS): Map<String, String> =
        spec.split(',').mapNotNull { pair ->
            val (key, value) = pair.split('=', limit = 2).map(String::trim).takeIf { it.size == 2 } ?: return@mapNotNull null
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }.toMap()
}
