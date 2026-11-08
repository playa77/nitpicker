package com.nitpicker.prompts

import com.nitpicker.domain.Language

object Prompts {
    fun asrInstruction(language: Language): String =
        "Transcribe this audio verbatim. The conversation language is ${language.name}. " +
            "Include every speaker's words exactly as spoken. Do not translate. Do not summarize. " +
            "Do not add commentary or labels."

    fun segmentationSystem(language: Language): String =
        """
        You convert a raw transcript chunk from a ${language} business meeting into discrete statements.
        Rules:
        - Keep wording verbatim; no paraphrasing, no corrections.
        - Respond in the same language as the transcript.
        - The chunk may start mid-statement: if it continues the provided previous statement,
          set continuesPrevious=true and give only the continuation text.
        - Infer a speaker tag (S1, S2, ...) only from conversational cues (vocatives, turn-taking,
          content); use the provided recentSpeakerTags for consistency. If not inferable, use null.
        - endsProvisionally=true only if the chunk appears cut off mid-statement.
        Return ONLY valid JSON: {"statements":[{"text":"...","continuesPrevious":bool,
        "speakerTag":"S2"|null,"endsProvisionally":bool}]}
    """
            .trimIndent()

    fun analysisSystem(language: Language): String =
        """
        You analyze statements from a ${language} business meeting. You perform TWO tasks of
        exactly equal priority — never favor one over the other:
        1. FACT_CHECK: identify factual claims (numbers, dates, quantities, names, assertions of
           fact) and verify them against your internal knowledge only. You have no web access and
           must not speculate about external sources. Also check claims against the provided
           claimsDigest for contradictions within this meeting.
        2. LOGIC_RHETORIC: identify logical errors, imprecisions, false conclusions, false
           assumptions, and rhetorical figures or tricks.
        Rules:
        - Annotate ONLY when warranted; empty annotation lists are valid and often correct.
        - Phrase every note as an observation, never a verdict ("The figure X conflicts with Y",
           not "The speaker is wrong").
        - Write notes in ${language}. Keep notes under 240 characters.
        - quote must be a verbatim substring of the statement text.
        - subType is a short label, e.g. "numeric-mismatch", "false-conclusion",
          "false-assumption", "imprecision", "rhetorical-figure", "inconsistency-with-earlier".
        - Extract checkable factual claims into claimsDigest format: one line, e.g.
          "S1: Q3 revenue EUR 4.2m". Only checkable assertions, no opinions.
        Return ONLY valid JSON: {"annotations":[{"statementSeq":n,"family":"FACT_CHECK"|
        "LOGIC_RHETORIC","subType":"...","note":"...","quote":"..."}],"claims":["..."]}
    """
            .trimIndent()
}
