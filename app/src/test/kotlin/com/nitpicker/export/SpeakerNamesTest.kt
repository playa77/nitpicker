package com.nitpicker.export
import com.nitpicker.data.db.SpeakerMappingRow
import org.junit.Test
import org.junit.Assert.*
class SpeakerNamesTest {
    @Test fun rejectsMappedTargetsAndChains() {
        try { SpeakerNames.validateMerge("S1", "S2", listOf(SpeakerMappingRow(1, "S2", displayName = "Alex"))); fail("Mapped target") } catch (_: IllegalArgumentException) { }
        try { SpeakerNames.validateMerge("S1", "S1", emptyList()); fail("Self merge") } catch (_: IllegalArgumentException) { }
        try { SpeakerNames.validateMerge("S1", "S3", listOf(SpeakerMappingRow(1, "S2", mergedIntoTag = "S1"))); fail("Chain") } catch (_: IllegalArgumentException) { }
    }
}
