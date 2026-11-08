package com.nitpicker.export

import com.nitpicker.data.repository.SessionRepository
import com.nitpicker.data.db.SpeakerMappingRow
import com.nitpicker.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SpeakerNames {
    fun resolve(tag: String?, mappings: List<SpeakerMappingRow>): String {
        if (tag == null) return "Speaker?"
        val map = mappings.associateBy { it.rawTag }
        val mapping = map[tag]
        val target = mapping?.mergedIntoTag
        return mapping?.displayName ?: target?.let { map[it]?.displayName ?: "$it?" } ?: "$tag?"
    }
    fun validateMerge(from: String, to: String, mappings: List<SpeakerMappingRow>) {
        require(from.isNotBlank() && to.isNotBlank() && from != to) { "Choose two different speakers" }
        require(mappings.none { it.rawTag == to && (it.displayName != null || it.mergedIntoTag != null) }) {
            "Merge target must be unmapped"
        }
        require(mappings.none { it.mergedIntoTag == from }) { "Cannot create a merge chain" }
    }
}

class MarkdownExporter(private val repo: SessionRepository, private val sink: DocumentSink) {
    suspend fun export(sessionId: Long): ExportOutcome {
        val model = repo.sessionForDisplay(sessionId)
        val uri = sink.write(fileName(model), render(model))
        return ExportOutcome(sessionId, uri, model.statements.size, model.annotations.size)
    }
    companion object {
        private fun date(ms: Long, pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(Date(ms))
        fun timestamp(ms: Long): String = "%02d:%02d:%02d".format(Locale.ROOT, ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)
        fun fileName(model: ExportModel) = "protocol-${date(model.session.startedAt, "yyyy-MM-dd-HHmm")}-${model.session.language}.md"
        fun render(model: ExportModel): String = buildString {
            val s = model.session
            appendLine("# Meeting Protocol — ${date(s.startedAt, "yyyy-MM-dd HH:mm")} (${s.language})")
            appendLine("- Started: ${date(s.startedAt, "HH:mm:ss")} · Ended: ${s.endedAt?.let { date(it, "HH:mm:ss") } ?: "—"} · Duration: ${((s.endedAt ?: System.currentTimeMillis()) - s.startedAt) / 60000} min")
            appendLine("- Language: ${s.language} · ASR: ${s.asrModel} · Statements: ${model.statements.size} · Annotations: ${model.annotations.size}")
            appendLine()
            val events = mutableListOf<Pair<Long, String>>()
            model.chunks.filter { it.state == ChunkState.FAILED }.forEach {
                events += it.startedAtOffsetMs to "[transcription gap ${timestamp(it.startedAtOffsetMs)}]\n"
            }
            model.chunks.firstOrNull()?.takeIf { it.startedAtOffsetMs > 20 }?.let {
                events += 0L to "[recording gap ${timestamp(0)}–${timestamp(it.startedAtOffsetMs)}]\n"
            }
            model.chunks.zipWithNext().filter { (a,b) -> b.startedAtOffsetMs > a.startedAtOffsetMs + a.durationMs + 20 }.forEach { (a,b) ->
                events += (a.startedAtOffsetMs + a.durationMs) to "[recording gap ${timestamp(a.startedAtOffsetMs + a.durationMs)}–${timestamp(b.startedAtOffsetMs)}]\n"
            }
            model.statements.forEach { row ->
                events += row.startedAtOffsetMs to buildString {
                    appendLine("**[${timestamp(row.startedAtOffsetMs)}] ${SpeakerNames.resolve(row.speakerTag, model.mappings)}** ${row.text}${if (row.state == StatementState.PROVISIONAL) "…" else ""}")
                    model.annotations.filter { it.statementId == row.id }.forEach { a ->
                        appendLine("*${if (a.family == AnnotationFamily.FACT_CHECK) "Fact-check" else "Logic"}: ${a.subType} — ${a.note}*")
                    }
                }
            }
            events.sortedBy { it.first }.forEach { appendLine(it.second) }
        }
    }
}
