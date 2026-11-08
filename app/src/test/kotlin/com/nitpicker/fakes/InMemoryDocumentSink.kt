package com.nitpicker.fakes
import com.nitpicker.export.DocumentSink
class InMemoryDocumentSink : DocumentSink {
    val files = linkedMapOf<String, String>()
    override suspend fun write(fileName: String, content: String): String {
        files[fileName] = content
        return "memory://$fileName"
    }
}
