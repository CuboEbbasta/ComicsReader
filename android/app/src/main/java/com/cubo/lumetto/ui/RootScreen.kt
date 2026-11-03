package com.cubo.lumetto.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.MainActivity
import com.cubo.lumetto.data.AppRouter
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LibraryNavigator
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.ui.library.MetadataEditorScreen
import com.cubo.lumetto.ui.library.SearchScreen
import com.cubo.lumetto.ui.library.SpatialLibraryScreen
import com.cubo.lumetto.ui.reader.ReaderContainer
import com.cubo.lumetto.ui.settings.SettingsScreen

@Composable
fun RootScreen(activity: MainActivity) {
    Box(Modifier.fillMaxSize().background(LColors.background)) {
        if (FileAccess.rootUri != null) LibraryHost(activity) else FolderPickerPrompt()
    }
}

/** Libreria + tutto ciò che vi si apre sopra (lettore, ricerca, impostazioni, metadati). */
@Composable
private fun LibraryHost(activity: MainActivity) {
    val root = FileAccess.rootUri
    LaunchedEffect(root) {
        if (LibraryStore.root == null) LibraryStore.refresh()
    }
    LaunchedEffect(LibraryStore.root) { LibraryNavigator.attach(LibraryStore.root) }
    LaunchedEffect(IncognitoStore.isActive, IncognitoStore.hidden) { LibraryStore.incognitoChanged() }

    val reading = AppRouter.openedComic
    val overlayOpen = reading != null || AppRouter.showSearch || AppRouter.showSettings || AppRouter.editingComic != null
    BackHandler(enabled = !overlayOpen && LibraryNavigator.canGoUp) { LibraryNavigator.goUp() }

    SpatialLibraryScreen()

    Overlay(AppRouter.showSearch) {
        SearchScreen(onClose = { AppRouter.showSearch = false })
    }
    Overlay(AppRouter.showSettings) {
        SettingsScreen(activity, onClose = { AppRouter.showSettings = false })
    }
    AppRouter.editingComic?.let { comic ->
        Overlay(true) { MetadataEditorScreen(comic, onClose = { AppRouter.editingComic = null }) }
    }
    if (reading != null) {
        androidx.compose.runtime.key(reading.id) {
            ReaderContainer(reading, onClose = { AppRouter.openedComic = null })
        }
    }
}

@Composable
private fun Overlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it / 8 } + fadeIn(),
        exit = slideOutVertically { it / 8 } + fadeOut(),
    ) {
        Box(Modifier.fillMaxSize().background(LColors.panel).clickable(indication = null, interactionSource = null) {}) {
            content()
        }
    }
}

/** Primo avvio: scelta della cartella principale, con il logo animato al centro. */
@Composable
fun FolderPickerPrompt() {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) FileAccess.setRoot(uri)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val logo = minOf(190.dp, maxHeight * 0.24f, maxWidth * 0.45f)
        // Logo grande ed esattamente al centro dello schermo; testo e pulsante sotto.
        Box(Modifier.align(Alignment.Center)) { AnimatedBrandLogo(logo) }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = maxHeight / 2 + logo / 2 + 6.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Lumetto", color = LColors.textPrimary, fontSize = 40.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
            Text(
                "Scegli la cartella che contiene i tuoi fumetti. L'app ne rispetta la struttura, a qualunque profondità, senza spostare o copiare nulla.",
                color = LColors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp),
            )
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(LColors.accent)
                    .clickable { picker.launch(null) }
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Folder, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Text("Scegli la cartella", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            FileAccess.lastError?.let {
                Text(it, color = LColors.error, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
