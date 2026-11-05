package com.cubo.lumetto.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cubo.lumetto.ui.LColors
import com.cubo.lumetto.ui.settings.FormButton
import com.cubo.lumetto.ui.settings.FormDivider
import com.cubo.lumetto.ui.settings.FormRow
import com.cubo.lumetto.ui.settings.FormSection
import com.cubo.lumetto.ui.settings.FormToggle
import com.cubo.lumetto.ui.settings.FormValue

/** Impostazioni → Accesso AI: attiva il server locale, mostra indirizzo e token, copia la configurazione. */
@Composable
fun AiSettingsScreen() {
    val clipboard = LocalClipboardManager.current
    var confirmRegenerate by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    val urls = AiAccess.urls()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        FormSection(
            "Accesso AI",
            footer = "Permette a un'AI (Claude, ChatGPT, agenti locali…) di leggere la tua libreria e usare Lumetto tramite MCP o REST. " +
                "Resta spento finché non lo attivi; ogni richiesta richiede il token qui sotto.",
        ) {
            FormToggle("Attiva accesso AI", AiAccess.enabled) { AiAccess.enabled = it }
            FormDivider()
            FormValue("Stato", AiAccess.statusMessage)
            FormDivider()
            FormToggle("Consenti modifiche", AiAccess.allowWrites) { AiAccess.allowWrites = it }
        }
        Text(
            "Con «Consenti modifiche» l'AI può cambiare metadati, progressi, segnalibri e impostazioni, e aprire fumetti. " +
                "Senza, può solo leggere.",
            color = LColors.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
        )

        FormSection(
            "Rete",
            footer = if (AiAccess.lanAccess) "Raggiungibile da dispositivi sulla stessa rete Wi-Fi. Non esporre mai la porta su Internet."
            else "Raggiungibile solo da questo telefono (per esempio con adb reverse).",
        ) {
            FormToggle("Consenti dalla rete di casa", AiAccess.lanAccess) { AiAccess.lanAccess = it }
            FormDivider()
            FormValue("Porta", AiAccess.port.toString())
            if (urls.isEmpty()) {
                FormDivider()
                FormValue("Indirizzo", "Wi-Fi non trovato")
            }
            urls.forEach { u ->
                FormDivider()
                FormValue("Indirizzo", u)
            }
        }

        FormSection("Collegare un'AI", footer = "Il token è come una password: chi lo ha può usare gli strumenti attivi. Rigeneralo se pensi sia trapelato.") {
            FormValue("Token", AiAccess.token.take(8) + "…")
            FormDivider()
            FormButton("Copia token") { clipboard.setText(AnnotatedString(AiAccess.token)); copied = "Token copiato" }
            FormDivider()
            FormButton("Copia configurazione MCP") { clipboard.setText(AnnotatedString(AiAccess.mcpConfig())); copied = "Configurazione copiata" }
            FormDivider()
            FormButton("Rigenera token", color = LColors.error) { confirmRegenerate = true }
            copied?.let {
                FormDivider()
                FormRow { Text(it, color = LColors.textSecondary) }
            }
        }

        FormSection("Ultime richieste", footer = "Le ultime chiamate ricevute dall'AI, per controllo.") {
            if (AiAccess.log.isEmpty()) {
                FormRow { Text("Nessuna richiesta", color = LColors.textTertiary) }
            } else {
                AiAccess.log.take(15).forEachIndexed { i, line ->
                    if (i > 0) FormDivider()
                    FormRow { Text(line, color = LColors.textSecondary, fontSize = 13.sp) }
                }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Rigenerare il token?") },
            text = { Text("Le AI già configurate smetteranno di funzionare finché non aggiorni il token.") },
            confirmButton = { TextButton({ AiAccess.regenerateToken(); confirmRegenerate = false; copied = null }) { Text("Rigenera") } },
            dismissButton = { TextButton({ confirmRegenerate = false }) { Text("Annulla") } },
        )
    }
}
