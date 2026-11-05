package com.cubo.lumetto.ui.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cubo.lumetto.BuildConfig
import com.cubo.lumetto.MainActivity
import com.cubo.lumetto.archive.ImageDecoder
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.CoverCache
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.LibraryStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.model.EpubFont
import com.cubo.lumetto.model.IncognitoAuthMethod
import com.cubo.lumetto.model.LibraryBackground
import com.cubo.lumetto.model.PageLayoutMode
import com.cubo.lumetto.model.PageTransitionStyle
import com.cubo.lumetto.model.ReadingDirection
import com.cubo.lumetto.model.readingDuration
import com.cubo.lumetto.sync.SyncManager
import com.cubo.lumetto.sync.SyncSettingsScreen
import com.cubo.lumetto.ui.BrandIcon
import com.cubo.lumetto.ui.LColors
import com.cubo.lumetto.ui.library.SheetTopBar
import com.cubo.lumetto.ui.library.lumettoFieldColors
import com.cubo.lumetto.ui.library.BackgroundImageStore
import java.io.FileOutputStream

// ---------------------------------------------------------------------------------------------
// Mattoni dei moduli (stile "Form" dell'iPad)

@Composable
fun FormSection(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        if (title != null) {
            Text(title.uppercase(), color = LColors.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LColors.card), content = content)
        if (footer != null) {
            Text(footer, color = LColors.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun FormRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun FormDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = LColors.stroke)

@Composable
fun FormButton(text: String, color: Color = LColors.accent, enabled: Boolean = true, onClick: () -> Unit) {
    FormRow(onClick = if (enabled) onClick else null) {
        Text(text, color = if (enabled) color else LColors.textTertiary)
    }
}

@Composable
fun FormValue(label: String, value: String) {
    FormRow {
        Text(label, color = LColors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, color = LColors.textSecondary)
    }
}

@Composable
fun FormToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FormRow(onClick = { onChange(!checked) }) {
        Text(label, color = LColors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = LColors.accent, checkedThumbColor = Color.White))
    }
}

@Composable
fun <T> FormPicker(label: String, value: T, options: List<T>, name: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FormRow(onClick = { open = true }) {
        Text(label, color = LColors.textPrimary, modifier = Modifier.weight(1f))
        Column {
            Text(name(value), color = LColors.accent)
            DropdownMenu(open, { open = false }, containerColor = LColors.surface) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(name(o), color = if (o == value) LColors.accent else LColors.textPrimary) }, onClick = { open = false; onChange(o) })
                }
            }
        }
    }
}

@Composable
fun FormLink(label: String, detail: String? = null, onClick: () -> Unit) {
    FormRow(onClick = onClick) {
        Text(label, color = LColors.textPrimary, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, color = LColors.textSecondary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LColors.textTertiary)
    }
}

// ---------------------------------------------------------------------------------------------

private enum class SettingsPage { MAIN, SYNC, STATS, AI }

@Composable
fun SettingsScreen(activity: MainActivity, onClose: () -> Unit) {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    BackHandler { if (page == SettingsPage.MAIN) onClose() else page = SettingsPage.MAIN }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        when (page) {
            SettingsPage.MAIN -> {
                SheetTopBar("Impostazioni", trailing = "Fine", onTrailing = onClose)
                MainSettings(activity, openSync = { page = SettingsPage.SYNC }, openStats = { page = SettingsPage.STATS }, openAi = { page = SettingsPage.AI })
            }
            SettingsPage.AI -> {
                SheetTopBar("Accesso AI", leading = "Indietro", onLeading = { page = SettingsPage.MAIN })
                com.cubo.lumetto.ai.AiSettingsScreen()
            }
            SettingsPage.SYNC -> {
                SheetTopBar("Sincronizzazione", leading = "Indietro", onLeading = { page = SettingsPage.MAIN })
                SyncSettingsScreen()
            }
            SettingsPage.STATS -> {
                SheetTopBar("Statistiche", leading = "Indietro", onLeading = { page = SettingsPage.MAIN })
                StatsScreen()
            }
        }
    }
}

@Composable
private fun MainSettings(activity: MainActivity, openSync: () -> Unit, openStats: () -> Unit, openAi: () -> Unit) {
    val context = LocalContext.current
    var confirmFolder by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    var unlockDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            LibraryStore.reset()
            FileAccess.setRoot(uri)
            LibraryStore.refresh()
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ok = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val bmp = ImageDecoder.decode(bytes, 1600)!!
                FileOutputStream(BackgroundImageStore.file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }.isSuccess
            if (ok) {
                AppSettings.libraryBackground = LibraryBackground.CUSTOM
                AppSettings.backgroundImageVersion = AppSettings.backgroundImageVersion + 1
            } else {
                message = "Non è stato possibile usare questa immagine."
            }
        }
    }

    fun enterIncognito() {
        if (!AppSettings.incognitoRequiresAuth) { IncognitoStore.enter(); return }
        if (AppSettings.incognitoAuthMethod == IncognitoAuthMethod.PIN) {
            if (IncognitoStore.hasPin) unlockDialog = true else pinDialog = true
        } else {
            authenticate(activity) { ok -> if (ok) IncognitoStore.enter() else message = "Sblocco non riuscito." }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        FormSection("Libreria") {
            FormValue("Cartella", FileAccess.rootUri?.let { FileAccess.rootName(it) } ?: "—")
            FormDivider()
            FormButton("Cambia cartella principale…") { confirmFolder = true }
            FormDivider()
            FormRow {
                Text("Profondità massima delle cartelle", color = LColors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = { AppSettings.maxFolderDepth = maxOf(1, AppSettings.maxFolderDepth - 1) }) { Text("−", color = LColors.accent, fontSize = 18.sp) }
                Text("${AppSettings.maxFolderDepth}", color = LColors.textPrimary)
                TextButton(onClick = { AppSettings.maxFolderDepth = minOf(30, AppSettings.maxFolderDepth + 1) }) { Text("+", color = LColors.accent, fontSize = 18.sp) }
            }
            FormDivider()
            FormButton("Aggiorna la libreria ora", enabled = !LibraryStore.isScanning) { LibraryStore.refresh() }
            FormDivider()
            FormButton("Ricrea le copertine") {
                CoverCache.clear()
                LibraryStore.reset()
                LibraryStore.refresh()
            }
        }

        FormSection("Lettura", footer = "Con la direzione automatica, i file nelle cartelle che contengono \"manga\", in giapponese o marcati come manga si leggono da destra a sinistra. Dal lettore puoi forzare la direzione per il singolo fumetto.") {
            FormPicker("Direzione", AppSettings.readingDirection, ReadingDirection.entries, { it.displayName }) { AppSettings.readingDirection = it }
            FormDivider()
            FormPicker("Pagine", AppSettings.pageLayout, PageLayoutMode.entries, { it.displayName }) { AppSettings.pageLayout = it }
            FormDivider()
            FormPicker("Transizione", AppSettings.transition, PageTransitionStyle.entries, { it.displayName }) { AppSettings.transition = it }
        }

        FormSection("Aspetto") {
            FormPicker("Sfondo della libreria", AppSettings.libraryBackground, LibraryBackground.entries, { it.displayName }) {
                if (it == LibraryBackground.CUSTOM) imagePicker.launch("image/*") else AppSettings.libraryBackground = it
            }
            if (AppSettings.libraryBackground == LibraryBackground.CUSTOM) {
                FormDivider()
                FormButton("Scegli un'altra immagine…") { imagePicker.launch("image/*") }
            }
            FormDivider()
            FormPicker("Carattere EPUB", AppSettings.epubFont, EpubFont.entries, { it.displayName }) { AppSettings.epubFont = it }
        }

        FormSection("Privacy", footer = "Per nascondere un fumetto tienilo premuto nella libreria e scegli \"Nascondi\". I file non vengono spostati né modificati.") {
            if (IncognitoStore.isActive) {
                FormButton("Esci dalla modalità Incognito") { IncognitoStore.exit() }
            } else {
                FormButton("Entra in modalità Incognito") { enterIncognito() }
            }
            FormDivider()
            FormToggle("Richiedi sblocco", AppSettings.incognitoRequiresAuth) { AppSettings.incognitoRequiresAuth = it }
            if (AppSettings.incognitoRequiresAuth) {
                FormDivider()
                FormPicker("Metodo", AppSettings.incognitoAuthMethod, IncognitoAuthMethod.entries, { it.displayName }) { AppSettings.incognitoAuthMethod = it }
                if (AppSettings.incognitoAuthMethod == IncognitoAuthMethod.PIN) {
                    FormDivider()
                    FormButton(if (IncognitoStore.hasPin) "Cambia PIN" else "Imposta PIN") { pinDialog = true }
                }
            }
        }

        FormSection("Sincronizzazione", footer = "Progressi, segnalibri ed evidenziazioni passano direttamente tra i tuoi dispositivi sulla stessa rete Wi-Fi, cifrati. Nessun server.") {
            FormLink("Sincronizzazione", SyncManager.roomName ?: "Spenta", onClick = openSync)
        }

        FormSection("Accesso AI", footer = "Lascia che un'AI legga e usi Lumetto (MCP / REST). Spento di default.") {
            FormLink("Accesso AI", if (com.cubo.lumetto.ai.AiAccess.enabled) "Attivo" else "Spento", onClick = openAi)
        }

        FormSection("Dati") {
            FormLink("Statistiche di lettura", onClick = openStats)
            FormDivider()
            FormButton("Azzera progressi e statistiche", color = LColors.error) { confirmReset = true }
        }

        FormSection("Aiuto") {
            FormRow {
                BrandIcon(48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Lumetto", color = LColors.textPrimary, fontSize = 20.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)
                    Text("Versione ${BuildConfig.VERSION_NAME} per Android", color = LColors.textSecondary, fontSize = 13.sp)
                }
            }
            FormDivider()
            FormRow {
                Text(
                    "Nella libreria scorri a destra e a sinistra tra le cartelle sorelle; tocca \"Entra\" o trascina oltre il fondo per scendere, oltre l'inizio per risalire. Nel lettore tocca i bordi per girare pagina e il centro per i comandi; pizzica o tocca due volte per ingrandire.",
                    color = LColors.textSecondary, fontSize = 13.sp,
                )
            }
        }
    }

    if (confirmFolder) {
        AlertDialog(
            onDismissRequest = { confirmFolder = false },
            title = { Text("Cambiare cartella?") },
            text = { Text("La libreria verrà riscansionata. Progressi e segnalibri restano salvati e tornano validi se riscegli la stessa cartella.") },
            confirmButton = { TextButton(onClick = { confirmFolder = false; folderPicker.launch(null) }) { Text("Scegli un'altra cartella", color = LColors.accent) } },
            dismissButton = { TextButton(onClick = { confirmFolder = false }) { Text("Annulla", color = LColors.textSecondary) } },
            containerColor = LColors.surface,
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Azzerare tutto?") },
            text = { Text("Progressi, segnalibri e statistiche verranno eliminati anche sugli altri dispositivi della stanza.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; ProgressStore.resetAll() }) { Text("Azzera progressi, segnalibri e statistiche", color = LColors.error) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annulla", color = LColors.textSecondary) } },
            containerColor = LColors.surface,
        )
    }
    if (pinDialog) {
        PinDialog(title = "Nuovo PIN", confirm = "Salva", onDismiss = { pinDialog = false }) { pin ->
            if (pin.length >= 4) { IncognitoStore.setPin(pin); pinDialog = false; true } else false
        }
    }
    if (unlockDialog) {
        PinDialog(title = "Inserisci il PIN", confirm = "Sblocca", onDismiss = { unlockDialog = false }) { pin ->
            val ok = IncognitoStore.unlock(pin)
            if (ok) unlockDialog = false
            ok
        }
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK", color = LColors.accent) } },
            containerColor = LColors.surface,
        )
    }
}

@Composable
private fun PinDialog(title: String, confirm: String, onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(12); error = false },
                    label = { Text("PIN (almeno 4 cifre)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = lumettoFieldColors(),
                )
                if (error) Text("PIN non valido.", color = LColors.error, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (!onSubmit(pin)) error = true }) { Text(confirm, color = LColors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla", color = LColors.textSecondary) } },
        containerColor = LColors.surface,
    )
}

/** Sblocco con impronta/volto, con ripiego sul codice del telefono. */
fun authenticate(activity: MainActivity, done: (Boolean) -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val can = BiometricManager.from(activity).canAuthenticate(authenticators)
    if (can != BiometricManager.BIOMETRIC_SUCCESS) { done(false); return }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Mostra i contenuti nascosti")
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(info)
}

@Composable
private fun StatsScreen() {
    val store = ProgressStore
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        FormSection("Riepilogo") {
            FormValue("Fumetti finiti", "${store.completedCount}")
            FormDivider()
            FormValue("Tempo di lettura", readingDuration(store.totalReadingTime))
            FormDivider()
            FormValue("Pagine lette", "${store.totalPagesRead}")
            FormDivider()
            FormValue("Sessioni", "${store.sessions.size}")
        }
        FormSection("Ultime sessioni") {
            if (store.sessions.isEmpty()) {
                FormRow { Text("Nessuna sessione registrata.", color = LColors.textSecondary) }
            }
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.ITALIAN)
            store.sessions.take(30).forEachIndexed { i, s ->
                if (i > 0) FormDivider()
                FormRow {
                    Column {
                        Text(s.title, color = LColors.textPrimary, maxLines = 1)
                        Text(
                            "${fmt.format(java.util.Date(s.startedAt.toLong()))} · ${readingDuration(s.duration)} · ${s.pagesRead} pagine",
                            color = LColors.textSecondary, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        FormSection("Per fumetto") {
            val items = store.progress.values.filter { it.totalReadingTime > 0 }.sortedByDescending { it.lastReadAt ?: 0.0 }.take(50)
            if (items.isEmpty()) FormRow { Text("Ancora niente.", color = LColors.textSecondary) }
            items.forEachIndexed { i, p ->
                if (i > 0) FormDivider()
                FormRow {
                    Column {
                        Text(p.relativePath.substringAfterLast('/').substringBeforeLast('.'), color = LColors.textPrimary, maxLines = 1)
                        val finish = p.timeToFinish?.let { " · Finito in ${readingDuration(it)}" } ?: ""
                        Text("Attivo: ${readingDuration(p.totalReadingTime)}$finish · ${p.sessionCount} sessioni", color = LColors.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
