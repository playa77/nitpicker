package com.nitpicker.export

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportIOException(cause: Throwable) : IOException("Could not export protocol", cause)
interface DocumentSink { suspend fun write(fileName: String, content: String): String }
class MediaStoreDocumentsSink(private val context: Context) : DocumentSink {
    override suspend fun write(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Nitpicker/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw ExportIOException(IOException("Document creation failed"))
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("Document unavailable")).bufferedWriter().use { it.write(content) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri.toString()
        } catch (e: Exception) { resolver.delete(uri, null, null); throw ExportIOException(e) }
    }
}
