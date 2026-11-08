package com.nitpicker.ui
import com.nitpicker.di.AppContainer
import com.nitpicker.data.db.SpeakerMappingRow
import com.nitpicker.export.SpeakerNames
import kotlinx.coroutines.flow.MutableStateFlow
class DetailViewModel(c: AppContainer, private val id: Long) : ScreenModel(c) {
    val model = protocol(id)
    val exportUri = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)
    fun rename(tag: String, name: String) = perform {
        require(tag.isNotBlank() && name.isNotBlank()) { "Enter a speaker tag and name" }
        c.repo.upsertMapping(SpeakerMappingRow(id, tag, displayName = name.trim()))
    }
    fun merge(from: String, to: String) = perform {
        val m = c.repo.sessionForExport(id)
        require(m.statements.any { it.speakerTag == from } && m.statements.any { it.speakerTag == to }) { "Choose existing speaker tags" }
        SpeakerNames.validateMerge(from, to, m.mappings)
        c.repo.upsertMapping(SpeakerMappingRow(id, from, mergedIntoTag = to))
    }
    fun reExport() = perform { val result = c.exporter.export(id); c.repo.setExportUri(id, result.uri); exportUri.value = result.uri }
    fun delete() = perform {
        c.repo.deleteSession(id); c.retention.deleteForSession(id); c.log.deleteForSession(id); deleted.value = true
    }
}
