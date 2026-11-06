package com.cubo.lumetto.sync

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.readingDuration
import com.cubo.lumetto.ui.LColors
import com.cubo.lumetto.ui.library.lumettoFieldColors
import com.cubo.lumetto.ui.settings.FormButton
import com.cubo.lumetto.ui.settings.FormDivider
import com.cubo.lumetto.ui.settings.FormRow
import com.cubo.lumetto.ui.settings.FormSection
import com.cubo.lumetto.ui.settings.FormValue

private enum class LibraryFilter(val label: String) {
    ALL("Tutti"), MISSING("Mancano qui"), SAME("Uguali"), DIFFERENT("Diversi")
}

/**
 * Un dispositivo della stanza: statistiche di lettura fatte su di lui e confronto tra la sua libreria e la nostra
 * (✓ uguale, ↓ manca qui, ! versione diversa), con il download dei fumetti che mancano.
 */
@Composable
fun PeerScreen(peerId: String) {
    val sync = SyncManager
    val context = LocalContext.current
    val peer = sync.peers.firstOrNull { it.id == peerId }
    val name = sync.displayName(peerId)
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf<LibraryRow?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            LibraryStore.reset()
            FileAccess.setRoot(uri)
            LibraryStore.refresh()
        }
    }

    LaunchedEffect(peerId, peer != null) {
        if (peer != null && sync.remoteLibraries[peerId] == null) sync.loadLibrary(peerId)
    }

    val index = sync.remoteLibraries[peerId]
    val version = LibraryStore.version
    val hiddenPaths = IncognitoStore.hiddenPaths
    val showHidden = IncognitoStore.isActive
    val rows = remember(index, version, hiddenPaths, showHidden) {
        if (index == null) emptyList()
        else LibraryCompare.compare(index.files, LibraryStore.allComicsIncludingHidden().associate { it.relativePath to it.fileSizeBytes }, hiddenPaths, showHidden)
    }
    val missing = rows.filter { it.status == LibraryStatus.MISSING_HERE }
    val shown = rows.filter { r ->
        val byFilter = when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.MISSING -> r.status == LibraryStatus.MISSING_HERE
            LibraryFilter.SAME -> r.status == LibraryStatus.SAME
            LibraryFilter.DIFFERENT -> r.status == LibraryStatus.DIFFERENT
        }
        byFilter && (query.isBlank() || r.path.contains(query.trim(), ignoreCase = true))
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item {
            val mine = ProgressStore.sessions.filter { it.deviceId == peerId }
            FormSection("Statistiche di $name", footer = "Solo le letture fatte su questo dispositivo dopo l'aggiornamento alla sincronizzazione v2.") {
                FormValue("Sessioni", "${mine.size}")
                FormDivider()
                FormValue("Tempo di lettura", readingDuration(mine.sumOf { it.duration }))
                FormDivider()
                FormValue("Pagine lette", "${mine.sumOf { it.pagesRead }}")
                FormDivider()
                FormValue("Fumetti letti", "${mine.map { it.relativePath }.toSet().size}")
            }
        }
        item {
            FormSection("Libreria") {
                when {
                    peer == null && index == null -> FormRow { Text("Il dispositivo non è in rete: apri Lumetto lì, sulla stessa Wi-Fi.", color = LColors.textSecondary) }
                    peerId in sync.loadingLibraryFor -> FormRow {
                        CircularProgressIndicator(color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Leggo la libreria…", color = LColors.textSecondary)
                    }
                    sync.libraryErrors[peerId] != null -> {
                        FormRow { Text(sync.libraryErrors[peerId]!!, color = LColors.error, fontSize = 13.sp) }
                        FormDivider()
                        FormButton("Riprova") { sync.loadLibrary(peerId) }
                    }
                    index != null -> {
                        FormValue("Uguali", "${LibraryCompare.count(rows, LibraryStatus.SAME)}")
                        FormDivider()
                        FormValue("Mancano qui", "${missing.size}")
                        FormDivider()
                        FormValue("Versione diversa", "${LibraryCompare.count(rows, LibraryStatus.DIFFERENT)}")
                        FormDivider()
                        FormValue("Solo su questo dispositivo", "${LibraryCompare.count(rows, LibraryStatus.ONLY_HERE)}")
                        FormDivider()
                        FormButton("Aggiorna l'elenco", enabled = peer != null) { sync.loadLibrary(peerId) }
                        if (missing.isNotEmpty()) {
                            FormDivider()
                            FormButton("Scarica tutti quelli che mancano (${missing.size})", enabled = peer != null) {
                                sync.download(missing.mapNotNull { it.remote }, peerId)
                            }
                        }
                        if (!FileAccess.canWrite) {
                            FormDivider()
                            FormRow { Text("Per scaricare Lumetto deve poter scrivere nella cartella dei fumetti: scegline di nuovo la stessa e concedi l'accesso.", color = LColors.textSecondary, fontSize = 13.sp) }
                            FormDivider()
                            FormButton("Scegli di nuovo la cartella dei fumetti") { folderPicker.launch(null) }
                        }
                    }
                }
            }
        }
        if (index != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LibraryFilter.entries.forEach { f ->
                        Text(
                            f.label,
                            color = if (f == filter) LColors.accent else LColors.textSecondary,
                            fontWeight = if (f == filter) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { filter = f }.padding(vertical = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    query, { query = it }, label = { Text("Cerca") }, singleLine = true,
                    colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
            items(shown, key = { it.path }) { row ->
                val state = sync.downloads[row.path]
                FormRow {
                    Column(Modifier.weight(1f)) {
                        Text(row.path.substringAfterLast('/').substringBeforeLast('.'), color = LColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val folder = row.path.substringBeforeLast('/', "")
                        val size = if (row.status == LibraryStatus.DIFFERENT) "qui ${row.localSize} B, là ${row.remote?.size} B"
                        else (row.remote?.size ?: row.localSize)?.let { Formatter.formatShortFileSize(context, it) } ?: ""
                        Text(
                            listOf(folder, size).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = LColors.textTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (state is SyncManager.DownloadState.Failed) Text(state.message, color = LColors.error, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        state is SyncManager.DownloadState.Running -> {
                            CircularProgressIndicator(progress = { state.fraction.toFloat() }, color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        }
                        state is SyncManager.DownloadState.Queued -> Text("In coda", color = LColors.textSecondary, fontSize = 13.sp)
                        row.status == LibraryStatus.SAME -> Icon(Icons.Filled.CheckCircle, "Uguale", tint = LColors.accent, modifier = Modifier.size(22.dp))
                        row.status == LibraryStatus.ONLY_HERE -> Text("Solo qui", color = LColors.textTertiary, fontSize = 13.sp)
                        row.status == LibraryStatus.MISSING_HERE -> Icon(
                            Icons.Filled.Download, "Scarica", tint = if (peer != null) LColors.accent else LColors.textTertiary,
                            modifier = Modifier.size(26.dp).clickable(enabled = peer != null) { row.remote?.let { sync.download(listOf(it), peerId) } },
                        )
                        row.status == LibraryStatus.DIFFERENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, "Versione diversa", tint = LColors.gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Download, "Sostituisci", tint = if (peer != null) LColors.accent else LColors.textTertiary,
                                modifier = Modifier.size(26.dp).clickable(enabled = peer != null) { confirmReplace = row },
                            )
                        }
                    }
                }
                FormDivider()
            }
        }
    }

    confirmReplace?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmReplace = null },
            title = { Text("Sostituire il file?") },
            text = { Text("Su questo dispositivo «${row.path.substringAfterLast('/')}» ha una dimensione diversa. Scaricandolo, la tua copia viene sostituita.") },
            confirmButton = { TextButton(onClick = { row.remote?.let { sync.download(listOf(it), peerId) }; confirmReplace = null }) { Text("Sostituisci", color = LColors.error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = null }) { Text("Annulla", color = LColors.textSecondary) } },
            containerColor = LColors.surface,
        )
    }
}
