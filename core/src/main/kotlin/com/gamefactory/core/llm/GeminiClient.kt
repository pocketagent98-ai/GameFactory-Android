package com.gamefactory.core.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Gemini client (generativelanguage.googleapis.com, v1beta generateContent).
 * The API key is supplied by a lambda so it can live in a secure store -
 * it is never a constructor constant and never appears in code.
 */
class GeminiClient(
    private val apiKeySupplier: () -> String?,
    private val model: String = "gemini-2.0-flash",
    override val supportedCapabilities: Set<LlmCapability> = setOf(
        LlmCapability.PLANNING, LlmCapability.CODING, LlmCapability.QA,
        LlmCapability.SECURITY, LlmCapability.LISTING
    ),
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000
) : LlmClient {

    override val name: String = "gemini:$model"

    override fun complete(request: LlmRequest): String {
        val apiKey = apiKeySupplier()?.takeIf { it.isNotBlank() }
            ?: throw LlmException(name, "no API key configured")

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") +
                ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")
        )
        val escapedSystem = jsonEscape(request.systemPrompt)
        val escapedUser = jsonEscape(request.userPrompt)
        val body = """
            {"systemInstruction":{"parts":[{"text":"$escapedSystem"}]},
             "contents":[{"parts":[{"text":"$escapedUser"}]}],
             "generationConfig":{"maxOutputTokens":${request.maxTokens},"temperature":0.4}}
        """.trimIndent()

        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val err = streamToString(conn.errorStream)
                throw LlmException(name, "HTTP $code: ${err.take(400)}")
            }
            val response = streamToString(conn.inputStream)
            extractFirstText(response)
                ?: throw LlmException(name, "no text in response: ${response.take(200)}")
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException(name, "network error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun streamToString(stream: java.io.InputStream?): String =
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    /** Minimal lenient extraction of the first "text" value in the Gemini JSON. */
    internal fun extractFirstText(json: String): String? {
        val key = "\"text\""
        var idx = json.indexOf(key)
        while (idx >= 0) {
            val colon = json.indexOf(':', idx + key.length)
            if (colon < 0) return null
            var i = colon + 1
            while (i < json.length && json[i] == ' ') i++
            if (i < json.length && json[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < json.length) {
                    val c = json[i]
                    when {
                        c == '\\' && i + 1 < json.length -> {
                            val n = json[i + 1]
                            when (n) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                                'u' -> {
                                    if (i + 5 < json.length) {
                                        sb.append(json.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                                    }
                                }
                                else -> sb.append(n)
                            }
                            i += 2
                        }
                        c == '"' -> return sb.toString()
                        else -> { sb.append(c); i++ }
                    }
                }
            }
            idx = json.indexOf(key, idx + key.length)
        }
        return null
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
}
