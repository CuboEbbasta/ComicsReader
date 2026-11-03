package com.cubo.lumetto.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.ui.LColors
import com.cubo.lumetto.ui.library.lumettoFieldColors
import com.cubo.lumetto.ui.settings.FormButton
import com.cubo.lumetto.ui.settings.FormDivider
import com.cubo.lumetto.ui.settings.FormRow
import com.cubo.lumetto.ui.settings.FormSection
import com.cubo.lumetto.ui.settings.FormToggle
import com.cubo.lumetto.ui.settings.FormValue

/** Impostazioni → Sincronizzazione: stanza (nome + password), dispositivi trovati, "Sincronizza ora". */
@Composable
fun SyncSettingsScreen() {
    val sync = SyncManager
    var room by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(sync.deviceName) }
    var confirmLeave by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { sync.setDeviceName(name) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val current = sync.roomName
        if (current != null) {
            FormSection("Stanza", footer = "Per aggiungere un dispositivo, entra nella stessa stanza con la stessa password.") {
                FormValue("Stanza", current)
                FormDivider()
                FormToggle("Sincronizzazione attiva", sync.isEnabled) { sync.isEnabled = it }
                FormDivider()
                FormButton("Esci dalla stanza", color = LColors.error) { confirmLeave = true }
            }
            FormSection("Dispositivi nella stanza") {
                when {
                    !sync.isEnabled -> FormRow { Text("Sincronizzazione in pausa.", color = LColors.textSecondary) }
                    sync.peers.isEmpty() -> FormRow {
                        CircularProgressIndicator(color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Cerco i dispositivi della stanza… Apri Lumetto sull'altro dispositivo, sulla stessa rete Wi-Fi.", color = LColors.textSecondary)
                    }
                    else -> sync.peers.forEachIndexed { i, peer ->
                        if (i > 0) FormDivider()
                        FormRow {
                            Icon(Icons.Filled.Devices, null, tint = LColors.accent)
                            Spacer(Modifier.width(10.dp))
                            Text(peer.name, color = LColors.textPrimary, modifier = Modifier.weight(1f))
                            when {
                                peer.id in sync.syncingPeerIds -> CircularProgressIndicator(color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                sync.lastSyncByPeer[peer.id] != null -> Text(
                                    DateUtils.getRelativeTimeSpanString(sync.lastSyncByPeer[peer.id]!!, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                    color = LColors.textSecondary, fontSize = 13.sp,
                                )
                                else -> Text("Mai", color = LColors.textTertiary, fontSize = 13.sp)
                            }
                        }
                    }
                }
                FormDivider()
                FormButton("Sincronizza ora", enabled = sync.peers.isNotEmpty() && sync.syncingPeerIds.isEmpty()) { sync.syncNow() }
                val error = sync.lastError
                val message = sync.lastMessage
                if (error != null) {
                    FormRow { Text(error, color = LColors.error, fontSize = 13.sp) }
                } else if (message != null) {
                    FormRow { Text(message, color = LColors.textSecondary, fontSize = 13.sp) }
                }
            }
        } else {
            FormSection(
                "Entra in una stanza",
                footer = "Scegli un nome e una password e usa gli stessi su ogni tuo dispositivo. La password cifra i dati: senza, nessuno può leggerli né inviarne. Resta solo in questo dispositivo, protetta dal Keystore di Android.",
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        room, { room = it }, label = { Text("Nome della stanza") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, imeAction = ImeAction.Next),
                        colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password (almeno 6 caratteri)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                FormDivider()
                val canJoin = room.isNotBlank() && password.length >= 6 && !sync.isJoining
                FormRow(onClick = if (canJoin) ({ sync.join(room, password); password = "" }) else null) {
                    Text("Entra nella stanza", color = if (canJoin) LColors.accent else LColors.textTertiary, modifier = Modifier.weight(1f))
                    if (sync.isJoining) CircularProgressIndicator(color = LColors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
                sync.lastError?.let { FormRow { Text(it, color = LColors.error, fontSize = 13.sp) } }
            }
        }

        FormSection("Questo dispositivo", footer = "È il nome che vedono gli altri dispositivi della stanza.") {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    name, { name = it.take(40) }, label = { Text("Nome di questo dispositivo") }, singleLine = true,
                    colors = lumettoFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        FormSection("Come funziona") {
            InfoRow(Icons.Outlined.CheckCircle, "Si sincronizzano", "Progressi, fumetti completati, segnalibri, statistiche di lettura, evidenziazioni EPUB, metadati modificati a mano, elenco dei nascosti in Incognito.")
            FormDivider()
            InfoRow(Icons.Outlined.Cancel, "Restano su ogni dispositivo", "Impostazioni, sfondo, PIN e sblocco, la cartella scelta e i fumetti stessi.")
            FormDivider()
            InfoRow(Icons.Filled.Folder, "Stessa struttura di cartelle", "I dati sono collegati al percorso di ogni fumetto dentro la cartella principale: mettili nelle stesse sottocartelle su ogni dispositivo.")
            FormDivider()
            InfoRow(Icons.Filled.Lock, "Privacy", "I dati viaggiano solo nella tua rete Wi-Fi, cifrati con la password della stanza. Nessun server, nessun account. Le reti pubbliche o universitarie spesso bloccano il collegamento tra dispositivi.")
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Uscire dalla stanza?") },
            text = { Text("Questo dispositivo smette di sincronizzarsi. I dati già presenti restano qui.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; sync.leave() }) { Text("Esci dalla stanza", color = LColors.error) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Annulla", color = LColors.textSecondary) } },
            containerColor = LColors.surface,
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = LColors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = LColors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text, color = LColors.textSecondary, fontSize = 13.sp)
        }
    }
}
