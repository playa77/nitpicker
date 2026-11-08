package com.nitpicker.prompts

import com.nitpicker.domain.Language
import org.junit.Assert.*
import org.junit.Test

class PromptsTest {
    @Test
    fun approvedPromptContractsHoldInBothLanguages() {
        Language.entries.forEach { language ->
            val a = Prompts.analysisSystem(language)
            val s = Prompts.segmentationSystem(language)
            val t = Prompts.asrInstruction(language)
            assertTrue(a.contains("exactly equal priority"))
            assertTrue(a.contains("internal knowledge only"))
            assertTrue(a.contains("no web access"))
            assertTrue(a.contains("FACT_CHECK"))
            assertTrue(a.contains("LOGIC_RHETORIC"))
            assertTrue(a.contains("verbatim substring"))
            assertTrue(a.contains("Write notes in $language"))
            assertTrue(a.contains("claimsDigest"))
            assertTrue(a.contains("observation, never a verdict"))
            assertTrue(s.contains("$language business meeting"))
            assertTrue(s.contains("continuesPrevious=true"))
            assertTrue(s.contains("no paraphrasing"))
            assertTrue(s.contains("recentSpeakerTags"))
            assertTrue(t.contains(language.name))
            assertTrue(t.contains("Do not translate"))
            assertTrue(t.contains("Do not summarize"))
        }
    }
}
