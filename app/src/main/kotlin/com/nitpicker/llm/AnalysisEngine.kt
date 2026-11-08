package com.nitpicker.llm

import com.nitpicker.domain.*
import com.nitpicker.prompts.Prompts
import kotlinx.serialization.json.*

class AnalysisEngine(private val transport: ChatTransport) {
    var onMalformed: () -> Unit = {}
    suspend fun analyze(ctx: AnalysisJobContext): AnalysisResult {
        fun rows(rows: List<com.nitpicker.data.db.StatementRow>) = JsonArray(rows.map { s ->
            buildJsonObject { put("seq", s.seq); put("text", s.text) }
        })
        val user = buildJsonObject {
            put("statements", rows(ctx.targets)); put("recentWindow", rows(ctx.window))
            put("claimsDigest", JsonArray(ctx.digest.map { JsonPrimitive(it.compactText) }))
            put("language", ctx.language.name)
        }
        val payload = transport.structured<AnalysisPayload>(Prompts.analysisSystem(ctx.language), user)
        val annotations = payload.annotations.mapNotNull { a ->
            val target = ctx.targets.find { it.seq == a.statementSeq }
            val family = AnnotationFamily.entries.find { it.name == a.family }
            if (target == null || family == null) { onMalformed(); null }
            else AnalysisAnnotationSpec(target.id, target.seq, family, a.subType, a.note,
                a.quote?.takeIf { target.text.contains(it) })
        }
        return AnalysisResult(ctx.job.id, annotations, payload.claims)
    }
}
