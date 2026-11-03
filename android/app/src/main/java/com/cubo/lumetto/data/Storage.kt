package com.cubo.lumetto.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File

/** Contesto dell'app e posizioni dei file, inizializzati all'avvio. */
object AppContext {
    lateinit var context: Context
        private set

    val prefs: SharedPreferences by lazy { context.getSharedPreferences("lumetto", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

/** Piccoli documenti JSON nella cartella privata dell'app (scrittura atomica). */
object JsonFile {
    private val directory: File by lazy { File(AppContext.context.filesDir, "data").apply { mkdirs() } }

    fun file(name: String) = File(directory, name)

    fun read(name: String): String? = try {
        val f = file(name)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    fun write(name: String, text: String) {
        try {
            val target = file(name)
            val tmp = File(directory, "$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}

/** Raggruppa scritture frequenti (un cambio pagina dopo l'altro) in un'unica scrittura. */
class Debouncer(private val delayMs: Long) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(action: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            action()
        }
        pending = r
        handler.postDelayed(r, delayMs)
    }

    fun flush() {
        val r = pending ?: return
        handler.removeCallbacks(r)
        pending = null
        r.run()
    }
}

/** Esegue sul thread principale. */
val mainHandler = Handler(Looper.getMainLooper())

fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

/** Hash stabile tra un avvio e l'altro (FNV-1a 64 bit, come sull'iPad). */
fun fnv1a(text: String): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (b in text.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return java.lang.Long.toUnsignedString(hash, 16)
}
