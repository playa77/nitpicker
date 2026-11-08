package com.nitpicker.pipeline

import com.nitpicker.domain.PipelineSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PipelineMetrics {
    private val mutable = MutableStateFlow(PipelineSnapshot())
    val snapshot = mutable.asStateFlow()

    fun update(f: (PipelineSnapshot) -> PipelineSnapshot) {
        mutable.update(f)
    }
}
