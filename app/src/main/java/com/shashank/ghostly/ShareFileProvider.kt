package com.shashank.ghostly

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * A minimal, hand-rolled stand-in for androidx's `FileProvider` — the app deliberately ships no
 * third-party libraries, AndroidX included. Serves files out of a private cache folder to whichever
 * app the user picks from the share sheet, via a `content://` URI rather than a raw file path (which
 * Android has refused to hand to other apps since API 24).
 */
class ShareFileProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    /** Some share targets (Gmail among them) ask for a display name and size before attaching a
     *  file; a minimal cursor answering just those two columns is enough to satisfy them. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }

    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment?.takeIf { !it.contains('/') && !it.contains("..") }
            ?: throw IllegalArgumentException("Not a shareable file: $uri")
        return File(shareDir(requireNotNull(context)), name)
    }

    companion object {
        private const val AUTHORITY = "com.shashank.ghostly.share"

        /** Where card images are written before sharing; created on first use. */
        fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

        fun uriFor(fileName: String): Uri = Uri.parse("content://$AUTHORITY/$fileName")
    }
}
