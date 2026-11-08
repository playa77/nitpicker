package com.nitpicker.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object LlmJsonParser {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Match delimiters outside JSON strings, so braces and escaped quotes in transcripts
    // cannot truncate a payload. Prefix prose is not accepted; trailing prose is ignored.
    fun extract(input: String): String {
        val text =
            input.trim().let {
                if (it.startsWith("```")) it.substringAfter('\n', "").trimStart() else it
            }
        if (text.firstOrNull() != '{' && text.firstOrNull() != '[')
            throw MalformedLlmOutputException("Expected JSON object or array")
        val stack = ArrayDeque<Char>()
        var quoted = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else
                when (c) {
                    '"' -> quoted = true
                    '{',
                    '[' -> stack.addLast(c)
                    '}',
                    ']' -> {
                        if (stack.isEmpty() || stack.removeLast() != if (c == '}') '{' else '[')
                            throw MalformedLlmOutputException("Unbalanced JSON")
                        if (stack.isEmpty()) return text.substring(0, index + 1)
                    }
                }
        }
        throw MalformedLlmOutputException("Incomplete JSON")
    }

    inline fun <reified T> parse(input: String): T =
        try {
            json.decodeFromString<T>(extract(input))
        } catch (e: SerializationException) {
            throw MalformedLlmOutputException("Invalid JSON payload", e)
        }
}
