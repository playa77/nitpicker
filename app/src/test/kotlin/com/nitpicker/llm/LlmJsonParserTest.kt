package com.nitpicker.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LlmJsonParserTest {
    @Test
    fun decodesFencesTrailingTextAndStringDelimiters() {
        val valid =
            """{"statements":[{"text":"brace } and [ and \\"quote\\"","continuesPrevious":false,"speakerTag":null,"endsProvisionally":true}]}"""
                .replace("\\\\", "\\")
        val plain = LlmJsonParser.parse<SegmentationPayload>(valid)
        assertEquals(
            plain,
            LlmJsonParser.parse<SegmentationPayload>("```json\n$valid\n``` trailing text"),
        )
        assertEquals(plain, LlmJsonParser.parse<SegmentationPayload>("$valid trailing text"))
    }

    @Test
    fun malformedOrWrongShapeIsClassifiedForOneRepairByLaterClients() {
        listOf("", "not JSON", "{", "{]", "{}", "{\"statements\":42}", "```json\n{bad}\n```")
            .forEach { text ->
                try {
                    LlmJsonParser.parse<SegmentationPayload>(text)
                    fail(text)
                } catch (_: MalformedLlmOutputException) {}
            }
    }

    @Test
    fun audioPartsIncludeRequiredDefaultDiscriminators() {
        val part =
            LlmJsonParser.json
                .encodeToJsonElement(AudioPart(input_audio = AudioData("base64")))
                .jsonObject
        assertEquals("input_audio", part.getValue("type").jsonPrimitive.content)
        assertEquals(
            "wav",
            part.getValue("input_audio").jsonObject.getValue("format").jsonPrimitive.content,
        )
        assertEquals(
            "text",
            LlmJsonParser.json
                .encodeToJsonElement(TextPart(text = "instruction"))
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun chatDtosTolerateProviderMetadataAndNullableContent() {
        val response =
            LlmJsonParser.json.decodeFromString<ChatResponse>(
                """{"id":"provider-id","choices":[{"message":{"content":null},"finish_reason":"stop"}],"usage":{"total_tokens":2}}"""
            )
        assertNull(response.choices.single().message.content)
        assertEquals(2L, response.usage!!.total_tokens)
    }
}
