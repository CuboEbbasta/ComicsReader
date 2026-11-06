package com.cubo.lumetto.ai

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.ui.LColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Conferma sul telefono prima di ogni operazione sui file chiesta da un'AI (spostare, rinominare, creare cartelle).
 * Lo strumento resta in attesa della risposta; senza risposta entro il tempo massimo l'operazione è annullata.
 */
object AiConfirm {
    class Request(val title: String, val detail: String) {
        val result = CompletableDeferred<Boolean>()
    }

    var pending by mutableStateOf<Request?>(null)
        private set

    /** Da chiamare dal thread del server (si blocca finché l'utente risponde). */
    fun ask(title: String, detail: String, timeoutMs: Long = 55_000L): Boolean = runBlocking {
        val request = Request(title, detail)
        withContext(Dispatchers.Main) { pending = request }
        val ok = withTimeoutOrNull(timeoutMs) { request.result.await() } ?: false
        withContext(Dispatchers.Main) { if (pending === request) pending = null }
        ok
    }

    fun answer(request: Request, ok: Boolean) {
        request.result.complete(ok)
    }
}

@Composable
fun AiConfirmDialog() {
    val request = AiConfirm.pending ?: return
    AlertDialog(
        onDismissRequest = { AiConfirm.answer(request, false) },
        title = { Text("Un'AI chiede: ${request.title}") },
        text = { Text(request.detail) },
        confirmButton = { TextButton(onClick = { AiConfirm.answer(request, true) }) { Text("Consenti", color = LColors.accent) } },
        dismissButton = { TextButton(onClick = { AiConfirm.answer(request, false) }) { Text("Rifiuta", color = LColors.textSecondary) } },
        containerColor = LColors.surface,
    )
}
