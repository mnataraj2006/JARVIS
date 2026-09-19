package com.jarvis.mobileagent.capabilities

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class FileTransferManager(private val context: Context) {

    private val downloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir

    fun saveFile(fileName: String, base64Content: String): Pair<Boolean, String> {
        return try {
            val safeName = fileName.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
            val destFile = File(downloadsDir, safeName)
            val bytes = Base64.decode(base64Content, Base64.DEFAULT)

            FileOutputStream(destFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            Log.d("FileTransfer", "Saved file to: ${destFile.absolutePath} (${bytes.size} bytes)")
            Pair(true, destFile.absolutePath)
        } catch (e: Exception) {
            Log.e("FileTransfer", "Failed to save file: ${e.message}")
            Pair(false, "File write failed: ${e.message}")
        }
    }

    fun listReceivedFiles(): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val files = downloadsDir.listFiles() ?: return list
            for (f in files.sortedByDescending { it.lastModified() }.take(20)) {
                if (f.isFile) {
                    list.add(mapOf(
                        "name" to f.name,
                        "size" to f.length(),
                        "path" to f.absolutePath,
                        "modified" to f.lastModified()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e("FileTransfer", "Error listing files: ${e.message}")
        }
        return list
    }
}
