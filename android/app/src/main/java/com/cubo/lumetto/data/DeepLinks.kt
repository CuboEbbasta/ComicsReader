package com.cubo.lumetto.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.cubo.lumetto.MainActivity
import com.cubo.lumetto.R
import com.cubo.lumetto.model.ComicFile

/** Scelta del titolo che meglio corrisponde a una ricerca (logica pura, si prova con i test). */
object TitleMatch {
    private fun norm(s: String) = s.trim().lowercase()

    /** Indice del migliore: titolo uguale, poi che inizia così, poi che contiene tutte le parole; -1 se nessuno. */
    fun best(query: String, titles: List<String>): Int {
        val q = norm(query)
        if (q.isEmpty() || titles.isEmpty()) return -1
        val t = titles.map(::norm)
        t.indexOf(q).takeIf { it >= 0 }?.let { return it }
        t.indexOfFirst { it.startsWith(q) }.takeIf { it >= 0 }?.let { return it }
        val words = q.split(' ').filter { it.isNotEmpty() }
        t.indexOfFirst { title -> words.all { title.contains(it) } }.takeIf { it >= 0 }?.let { return it }
        return -1
    }
}

/**
 * Link per aprire un fumetto da fuori (collegamento sulla home, automazioni, scorciatoie):
 *  lumetto://apri?titolo=spiderman   cerca per titolo
 *  lumetto://apri?percorso=Manga%2FVol%2001.cbz   percorso esatto
 *  lumetto://apri?ultimo=1   l'ultimo che stavi leggendo
 */
object DeepLinks {
    class Request(val title: String?, val path: String?, val latest: Boolean)

    var pending by mutableStateOf<Request?>(null)

    fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "lumetto" || uri.host != "apri") return
        pending = Request(
            title = uri.getQueryParameter("titolo")?.trim()?.takeIf { it.isNotEmpty() },
            path = uri.getQueryParameter("percorso")?.takeIf { it.isNotEmpty() },
            latest = uri.getQueryParameter("ultimo") != null,
        )
        intent.data = null // non riaprirlo se l'attività viene ricreata
    }

    /** Il fumetto richiesto, o null se non c'è (la libreria deve essere già caricata). */
    fun resolve(r: Request): ComicFile? {
        r.path?.let { return LibraryStore.comic(it) }
        if (r.latest) return LibraryStore.continueReading(ProgressStore.progress).firstOrNull()?.first
        val q = r.title ?: return null
        val found = LibraryStore.search(q).comics
        val i = TitleMatch.best(q, found.map { LibraryStore.title(it) })
        return found.getOrNull(if (i >= 0) i else 0)
    }
}

/** Scorciatoie dell'icona (pressione lunga): gli ultimi fumetti letti, aperti con un tocco. */
object Shortcuts {
    fun update(context: Context) {
        val items = LibraryStore.continueReading(ProgressStore.progress).take(4).map { (c, _) ->
            val title = LibraryStore.title(c)
            ShortcutInfoCompat.Builder(context, "c" + fnv1a(c.relativePath))
                .setShortLabel(title.take(25))
                .setLongLabel(title.take(60))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("lumetto://apri?percorso=" + Uri.encode(c.relativePath)), context, MainActivity::class.java)
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, items) }
    }
}
