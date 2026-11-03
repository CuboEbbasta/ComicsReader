package com.cubo.lumetto.ui.library

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.LibraryNavigator
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.mainHandler
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ComicMetadata
import com.cubo.lumetto.ui.LColors

/** Barra in alto dei fogli: titolo al centro, azioni ai lati. */
@Composable
fun SheetTopBar(title: String, leading: String? = null, onLeading: () -> Unit = {}, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f)) { if (leading != null) TextButton(onClick = onLeading) { Text(leading, color = LColors.textSecondary) } }
        Text(title, color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            if (trailing != null) TextButton(onClick = onTrailing) { Text(trailing, color = LColors.accent, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun lumettoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LColors.accent,
    unfocusedBorderColor = LColors.track,
    cursorColor = LColors.accent,
    focusedLabelColor = LColors.accent,
    unfocusedLabelColor = LColors.textSecondary,
    focusedTextColor = LColors.textPrimary,
    unfocusedTextColor = LColors.textPrimary,
    focusedContainerColor = LColors.card,
    unfocusedContainerColor = LColors.card,
)

/** Ricerca globale: attraversa tutta la libreria e apre il risultato senza navigare a mano. */
@Composable
fun SearchScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val results = remember(query, LibraryStore.version) { LibraryStore.search(query) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        SheetTopBar("Cerca", trailing = "Fine", onTrailing = onClose)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Cerca nella libreria") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = LColors.accent) },
            singleLine = true,
            colors = lumettoFieldColors(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                item { Text("Cerca per nome del file, titolo, serie o autore in tutte le cartelle.", color = LColors.textSecondary) }
            } else if (results.isEmpty) {
                item { Text("Nessun risultato per “$query”.", color = LColors.textSecondary) }
            }
            if (results.folders.isNotEmpty()) {
                item { SectionLabel("Cartelle") }
                items(results.folders, key = { "f:" + it.id }) { folder ->
                    ResultRow(onClick = { LibraryNavigator.jump(folder); onClose() }) {
                        Icon(Icons.Filled.ViewModule, null, tint = LColors.accent, modifier = Modifier.size(28.dp))
                        Column {
                            Text(folder.name, color = LColors.textPrimary)
                            Text(folder.relativePath, color = LColors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (results.comics.isNotEmpty()) {
                item { SectionLabel("Fumetti") }
                items(results.comics, key = { "c:" + it.id }) { comic ->
                    ResultRow(onClick = {
                        onClose()
                        mainHandler.postDelayed({ AppRouter.open(comic) }, 300)
                    }) {
                        ComicCover(comic, Modifier.width(36.dp).height(54.dp), cornerRadius = 5.dp, showsTitle = false)
                        Column {
                            Text(LibraryStore.title(comic), color = LColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (comic.folderPath.isEmpty()) comic.fileName else "${comic.folderPath} / ${comic.fileName}",
                                color = LColors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), color = LColors.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun ResultRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LColors.card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Modifica manuale dei metadati (sovrapposti a quelli del file, che non viene toccato). */
@Composable
fun MetadataEditorScreen(comic: ComicFile, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val initial = remember(comic.id) { MetadataStore.metadata(comic) }
    var title by remember { mutableStateOf(initial.title ?: "") }
    var series by remember { mutableStateOf(initial.series ?: "") }
    var volume by remember { mutableStateOf(initial.volume ?: "") }
    var number by remember { mutableStateOf(initial.number ?: "") }
    var year by remember { mutableStateOf(initial.year?.toString() ?: "") }
    var writer by remember { mutableStateOf(initial.writer ?: "") }
    var penciller by remember { mutableStateOf(initial.penciller ?: "") }
    var summary by remember { mutableStateOf(initial.summary ?: "") }
    var manga by remember { mutableStateOf(initial.manga ?: false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        SheetTopBar("Metadati", leading = "Annulla", onLeading = onClose, trailing = "Salva", onTrailing = {
            fun n(s: String) = s.trim().ifEmpty { null }
            MetadataStore.saveEdit(
                ComicMetadata(
                    title = n(title), series = n(series), number = n(number), volume = n(volume),
                    writer = n(writer), penciller = n(penciller), summary = n(summary),
                    year = year.trim().toIntOrNull(), pageCount = initial.pageCount, languageISO = initial.languageISO, manga = manga,
                ),
                comic,
            )
            onClose()
        })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ComicCover(comic, Modifier.width(70.dp).height(105.dp), cornerRadius = 8.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comic.fileName, color = LColors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("${comic.format.displayName} · ${Formatter.formatShortFileSize(context, comic.fileSizeBytes)}", color = LColors.textSecondary, fontSize = 12.sp)
                    initial.pageCount?.let { Text("$it pagine", color = LColors.textSecondary, fontSize = 12.sp) }
                }
            }
            SectionLabel("Informazioni")
            Field("Titolo", title) { title = it }
            Field("Serie", series) { series = it }
            Field("Volume", volume) { volume = it }
            Field("Numero / capitolo", number) { number = it }
            Field("Anno", year, numeric = true) { year = it }
            SectionLabel("Autori")
            Field("Testi", writer) { writer = it }
            Field("Disegni", penciller) { penciller = it }
            SectionLabel("Trama")
            OutlinedTextField(
                summary, { summary = it }, label = { Text("Trama") }, minLines = 3, maxLines = 8,
                colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("È un manga (lettura destra → sinistra)", color = LColors.textPrimary, modifier = Modifier.weight(1f))
                Switch(manga, { manga = it }, colors = SwitchDefaults.colors(checkedTrackColor = LColors.accent))
            }
            TextButton(onClick = { MetadataStore.clearEdit(comic); onClose() }) {
                Text("Ripristina i metadati del file", color = LColors.error)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth(),
    )
}
