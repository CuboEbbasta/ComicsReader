package com.cubo.lumetto.ui.library

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.CoverCache
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.ComicFile
import com.cubo.lumetto.model.ReadingProgress
import com.cubo.lumetto.model.readingDuration
import com.cubo.lumetto.ui.LColors
import com.cubo.lumetto.ui.ProgressLine

/** Copertina caricata in modo asincrono; finché non c'è mostra una copertina generata dal titolo. */
@Composable
fun ComicCover(comic: ComicFile, modifier: Modifier = Modifier, cornerRadius: Dp = LColors.coverRadius, showsTitle: Boolean = true) {
    var image by remember(comic.id, comic.modifiedMs) { mutableStateOf<Bitmap?>(CoverCache.cached(comic)) }
    LaunchedEffect(comic.id, comic.modifiedMs) {
        if (image == null) image = CoverCache.cover(comic)
    }
    Box(modifier.clip(RoundedCornerShape(cornerRadius))) {
        Crossfade(targetState = image, label = "cover") { bmp ->
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedCover(comic.fileTitle, showsTitle, Modifier.fillMaxSize())
            }
        }
    }
}

private val coverPalettes = listOf(
    Triple(0xFF1E3A5F, 0xFF0A0F1C, 0xFFE8533F), Triple(0xFF2D6A4F, 0xFF081C15, 0xFFF4D35E),
    Triple(0xFF3A0CA3, 0xFF10002B, 0xFFF72585), Triple(0xFF4A5759, 0xFF161A1D, 0xFFEDE6D6),
    Triple(0xFF9D0208, 0xFF370617, 0xFFFFBA08), Triple(0xFF023E8A, 0xFF03045E, 0xFF90E0EF),
    Triple(0xFF6C584C, 0xFF2B2118, 0xFFF0EAD2), Triple(0xFF5A189A, 0xFF240046, 0xFFFF9E00),
)

/** Copertina generata con colori stabili per ogni titolo (come sull'iPad). */
@Composable
fun GeneratedCover(title: String, showsTitle: Boolean, modifier: Modifier = Modifier) {
    var seed = 0
    for (ch in title) seed = (seed * 31 + ch.code) and 0x7FFFFFFF
    val p = coverPalettes[seed % coverPalettes.size]
    BoxWithConstraints(modifier) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(listOf(Color(p.first), Color(p.second)), Offset.Zero, Offset(size.width, size.height)))
            drawCircle(Color(p.third).copy(alpha = 0.9f), radius = size.width * 0.31f, center = Offset(size.width * 0.62f, size.height * 0.34f))
        }
        if (showsTitle) {
            Text(
                title.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = maxOf(9f, w.value * 0.13f).sp,
                lineHeight = maxOf(10f, w.value * 0.14f).sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(w * 0.08f),
            )
        }
    }
}

private fun detail(comic: ComicFile, progress: ReadingProgress?): String {
    if (progress == null) return LibraryStore.subtitle(comic)
    val time = if (progress.totalReadingTime > 60) " · ${readingDuration(progress.totalReadingTime)}" else ""
    if (progress.isCompleted) return "Letto$time"
    return "p. ${progress.currentPage + 1}/${maxOf(progress.totalPages, 1)}$time"
}

/** Elemento della griglia: copertina con badge, titolo, informazioni, menu (pressione lunga). */
@Composable
fun ComicGridItem(comic: ComicFile, compact: Boolean) {
    val progress = ProgressStore.progress[comic.relativePath]
    MetadataStore.edits // lettura per ridisegnare quando cambiano i metadati
    MetadataStore.parsed
    val title = LibraryStore.title(comic)
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(8.dp, RoundedCornerShape(LColors.coverRadius))
                    .clip(RoundedCornerShape(LColors.coverRadius))
                    .combinedClickable(onClick = { AppRouter.open(comic) }, onLongClick = { menu = true })
                    .semantics { contentDescription = "Apri $title" }
            ) {
                ComicCover(comic, Modifier.fillMaxSize(), cornerRadius = 0.dp)
                Text(
                    comic.format.displayName,
                    color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (IncognitoStore.isHidden(comic.relativePath)) {
                        Icon(
                            Icons.Filled.VisibilityOff, null, tint = Color.White,
                            modifier = Modifier.size(18.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(3.dp),
                        )
                    }
                    if (ProgressStore.hasBookmarks(comic.relativePath)) {
                        Icon(Icons.Filled.Bookmark, null, tint = LColors.accent, modifier = Modifier.size(18.dp))
                    }
                }
                if (progress?.isCompleted == true) {
                    Icon(
                        Icons.Filled.Check, "Letto", tint = Color.Black,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(7.dp).size(22.dp)
                            .background(LColors.accent, CircleShape).padding(4.dp),
                    )
                } else if (progress != null && progress.fraction > 0) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.fraction.toFloat()).height(3.dp).background(LColors.accent))
                }
            }
            ComicMenu(comic, progress, menu) { menu = false }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = LColors.textPrimary, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail(comic, progress), color = LColors.textSecondary, fontSize = if (compact) 10.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Menu delle azioni su un fumetto. */
@Composable
fun ComicMenu(comic: ComicFile, progress: ReadingProgress?, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = LColors.surface) {
        DropdownMenuItem(text = { Text("Apri") }, onClick = { onDismiss(); AppRouter.open(comic) })
        if (progress?.isCompleted == true) {
            DropdownMenuItem(text = { Text("Segna come non letto") }, onClick = { onDismiss(); ProgressStore.setCompleted(false, comic.relativePath) })
        } else {
            DropdownMenuItem(text = { Text("Segna come letto") }, onClick = {
                onDismiss()
                ProgressStore.setCompleted(true, comic.relativePath, MetadataStore.metadata(comic).pageCount)
            })
        }
        DropdownMenuItem(text = { Text("Modifica metadati") }, onClick = { onDismiss(); AppRouter.editingComic = comic })
        DropdownMenuItem(
            text = { Text(if (IncognitoStore.isHidden(comic.relativePath)) "Mostra nella libreria" else "Nascondi (Incognito)") },
            onClick = { onDismiss(); IncognitoStore.toggleHidden(comic.relativePath) },
        )
        if (progress != null) {
            DropdownMenuItem(text = { Text("Azzera progresso", color = LColors.error) }, onClick = { onDismiss(); ProgressStore.resetProgress(comic.relativePath) })
        }
    }
}

/** Scheda orizzontale di "Continua a leggere". */
@Composable
fun ContinueReadingCard(comic: ComicFile, progress: ReadingProgress, compact: Boolean) {
    Row(
        Modifier
            .width(if (compact) 236.dp else 256.dp)
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .background(LColors.card)
            .combinedClickable(onClick = { AppRouter.open(comic) })
            .padding(if (compact) 10.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ComicCover(comic, Modifier.width(if (compact) 52.dp else 58.dp).height(if (compact) 78.dp else 86.dp), cornerRadius = 8.dp, showsTitle = false)
        Column(Modifier.height(if (compact) 78.dp else 86.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(LibraryStore.title(comic), color = LColors.textPrimary, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LibraryStore.subtitle(comic), color = LColors.textSecondary, fontSize = if (compact) 11.sp else 12.sp, maxLines = 1)
            Text("Pagina ${progress.currentPage + 1} di ${maxOf(progress.totalPages, 1)}", color = LColors.textSecondary, fontSize = if (compact) 11.sp else 12.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressLine(progress.fraction, Modifier.weight(1f))
                Text("${Math.round(progress.fraction * 100)}%", color = LColors.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.fillHeight() = this.fillMaxHeight()
