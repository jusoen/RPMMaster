package com.rpmmonitor.master.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File

private const val TAG = "ExportWriter"

/** Where a save ended up, or why it did not. */
sealed interface ExportResult {
    /** [location] is for display only — it is a description, not a path to reopen. */
    data class Saved(val location: String) : ExportResult
    data class Failed(val message: String) : ExportResult
}

/**
 * Writes the export to a file the user can actually get at.
 *
 * From Android 10 that means the shared Downloads collection through MediaStore,
 * which needs no permission and puts the file where a file manager will show it.
 * Below that, MediaStore has no Downloads collection to write to and the shared
 * directory needs a runtime permission this app does not ask for, so it falls back
 * to the app's own external files directory — still reachable over USB or adb,
 * without a permission prompt on a screen the user is watching an engine on.
 */
object ExportWriter {

    fun write(context: Context, fileName: String, content: String): ExportResult = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloads(context, fileName, content)
        } else {
            writeToAppFiles(context, fileName, content)
        }
    } catch (e: Exception) {
        // Storage can fail for reasons that are none of this app's business: no
        // media mounted, a provider that refuses, a full volume. None of them are
        // worth taking the process down over, and the message goes to the user.
        Log.e(TAG, "export failed", e)
        ExportResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    // The guard is in write(), one frame up, which the API check cannot see from
    // here. Declaring the requirement makes the contract explicit rather than
    // suppressing the warning.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(context: Context, fileName: String, content: String): ExportResult {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult.Failed("the Downloads collection refused the file")

        // On any failure past this point the pending row is deleted rather than left
        // behind: a zero-byte entry in Downloads is worse than no entry at all.
        try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: return ExportResult.Failed("could not open the file for writing")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return ExportResult.Saved("Downloads/$fileName")
    }

    private fun writeToAppFiles(context: Context, fileName: String, content: String): ExportResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: return ExportResult.Failed("no external storage available")
        // mkdirs returns false both for "already there" and for "could not". The
        // existence check afterwards is what actually decides.
        dir.mkdirs()
        if (!dir.isDirectory) return ExportResult.Failed("could not create ${dir.path}")
        val file = File(dir, fileName)
        file.writeText(content)
        return ExportResult.Saved(file.path)
    }
}
