package com.cubo.lumetto.data

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicFolder
import com.cubo.lumetto.model.ComicFormat
import com.cubo.lumetto.model.NaturalOrder

/**
 * Cartella principale scelta con il selettore di sistema. Il permesso resta valido ai lanci
 * successivi (permesso persistente): i fumetti non vengono mai copiati né spostati.
 */
object FileAccess {
    private const val KEY = "library.rootTreeUri"

    var rootUri by mutableStateOf<Uri?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)

    fun load() {
        val saved = AppContext.prefs.getString(KEY, null)?.let(Uri::parse) ?: return
        val stillGranted = AppContext.context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isReadPermission }
        if (stillGranted) {
            rootUri = saved
        } else {
            lastError = "Non è stato possibile riaprire la cartella principale. Selezionala di nuovo."
        }
    }

    fun setRoot(uri: Uri) {
        val resolver = AppContext.context.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            lastError = "Il sistema ha negato l'accesso alla cartella scelta."
            return
        }
        // Libera i permessi delle cartelle scelte in precedenza.
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        AppContext.prefs.edit().putString(KEY, uri.toString()).apply()
        lastError = null
        rootUri = uri
    }

    fun clearRoot() {
        AppContext.prefs.edit().remove(KEY).apply()
        rootUri = null
    }

    fun openFd(uri: Uri): ParcelFileDescriptor =
        AppContext.context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Impossibile aprire il file.")

    /** Nome leggibile della cartella principale. */
    fun rootName(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            AppContext.context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Libreria"
        } catch (e: Exception) {
            "Libreria"
        }
    }
}

/**
 * Scansione ricorsiva della cartella principale con le query di DocumentsContract (veloce, senza
 * aprire i file). Nessuna struttura imposta: qualunque profondità fino al limite impostato.
 */
object LibraryScanner {
    private class Raw(val name: String, val docId: String, val mime: String, val size: Long, val modified: Long)

    fun scan(treeUri: Uri, maxDepth: Int): ComicFolder {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = ComicFolder("", FileAccess.rootName(treeUri), 0, null)
        fill(treeUri, root, rootId, 0, maxOf(0, maxDepth))
        return root
    }

    private fun children(treeUri: Uri, docId: String): List<Raw> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<Raw>()
        AppContext.context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                if (name.startsWith(".")) continue
                result.add(
                    Raw(
                        name = name,
                        docId = c.getString(1) ?: continue,
                        mime = c.getString(2) ?: "",
                        size = if (c.isNull(3)) 0 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                    )
                )
            }
        }
        return result
    }

    private fun fill(treeUri: Uri, folder: ComicFolder, docId: String, depth: Int, maxDepth: Int) {
        val raw: List<Raw> = try {
            children(treeUri, docId)
        } catch (e: Exception) {
            if (depth == 0) throw e
            return
        }
        val items = raw.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

        for (item in items) {
            val childPath = if (folder.relativePath.isEmpty()) item.name else "${folder.relativePath}/${item.name}"
            if (item.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (depth >= maxDepth) continue
                val sub = ComicFolder(childPath, item.name, depth + 1, folder)
                // Una sottocartella illeggibile non deve far fallire l'intera scansione.
                runCatching { fill(treeUri, sub, item.docId, depth + 1, maxDepth) }
                if (sub.comics.isNotEmpty() || sub.subfolders.isNotEmpty()) folder.subfolders.add(sub)
            } else {
                val format = ComicFormat.fromExtension(item.name.substringAfterLast('.', "")) ?: continue
                folder.comics.add(
                    ComicFile(
                        relativePath = childPath,
                        fileName = item.name,
                        format = format,
                        fileSizeBytes = item.size,
                        modifiedMs = item.modified,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, item.docId),
                    )
                )
            }
        }
    }
}
