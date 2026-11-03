package com.cubo.lumetto.model

import android.net.Uri

/** Momento attuale in millisecondi dal 1970 (come le date del protocollo di sincronizzazione). */
fun nowMs(): Double = System.currentTimeMillis().toDouble()

/** UUID in maiuscolo, come quelli generati da Swift sull'iPad (le lapidi usano la stringa esatta). */
fun newUuid(): String = java.util.UUID.randomUUID().toString().uppercase()

enum class ComicFormat(val raw: String) {
    CBZ("cbz"), CBR("cbr"), PDF("pdf"), EPUB("epub");

    val displayName: String get() = raw.uppercase()

    companion object {
        fun fromExtension(ext: String): ComicFormat? = entries.firstOrNull { it.raw == ext.lowercase() }
    }
}

enum class ReadingDirection(val raw: String, val displayName: String, val shortName: String) {
    AUTO("auto", "Automatica", "Auto"),
    LEFT_TO_RIGHT("leftToRight", "Sinistra → destra", "SX → DX"),
    RIGHT_TO_LEFT("rightToLeft", "Destra → sinistra (manga)", "DX → SX");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class PageLayoutMode(val raw: String, val displayName: String, val shortName: String) {
    SINGLE("single", "Una pagina", "Una"),
    DOUBLE("double", "Due pagine", "Due");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** "pageCurl" esiste su iPad: qui viene mostrato come scorrimento con effetto pagina. */
enum class PageTransitionStyle(val raw: String, val displayName: String) {
    SLIDE("slide", "Scorrimento"),
    PAGE_CURL("pageCurl", "Sfoglia"),
    VERTICAL("vertical", "Verticale");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class ReaderTheme(val raw: String, val displayName: String, val hex: Long) {
    BLACK("black", "Nero", 0xFF000000),
    GRAPHITE("graphite", "Grafite", 0xFF1C1C1F),
    SEPIA("sepia", "Seppia", 0xFF2A2219),
    PAPER("paper", "Carta", 0xFFEFEAE0);

    val isLight: Boolean get() = this == PAPER

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class IncognitoAuthMethod(val raw: String, val displayName: String) {
    PIN("pin", "PIN"),
    BIOMETRICS("biometrics", "Impronta / sblocco del telefono");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class LibraryBackground(val raw: String, val displayName: String, val top: Long, val bottom: Long) {
    BLACK("black", "Nero", 0xFF000000, 0xFF000000),
    ANTHRACITE("anthracite", "Antracite", 0xFF17171A, 0xFF050506),
    MIDNIGHT("midnight", "Notte", 0xFF0B1426, 0xFF020409),
    EMBER("ember", "Brace", 0xFF1F0F08, 0xFF050302),
    CUSTOM("custom", "Immagine personale", 0xFF000000, 0xFF000000);

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class EpubFont(val raw: String, val displayName: String, val cssFamily: String?) {
    ORIGINAL("original", "Originale", null),
    SERIF("serif", "Serif", "Georgia, 'Noto Serif', serif"),
    SANS("sans", "Sans", "Roboto, 'Noto Sans', sans-serif");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

enum class HighlightColor(val raw: String, val displayName: String, val argb: Long, val css: String) {
    YELLOW("yellow", "Giallo", 0xFFFFD60A, "rgba(255,214,10,0.38)"),
    GREEN("green", "Verde", 0xFF4CD964, "rgba(76,217,100,0.34)"),
    BLUE("blue", "Azzurro", 0xFF5AC8FA, "rgba(90,200,250,0.34)"),
    PINK("pink", "Rosa", 0xFFFF6FA8, "rgba(255,111,168,0.36)");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** Metadati di un fumetto (ComicInfo.xml, OPF dell'EPUB, PDF o modifica manuale). */
data class ComicMetadata(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val volume: String? = null,
    val writer: String? = null,
    val penciller: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val pageCount: Int? = null,
    val languageISO: String? = null,
    val manga: Boolean? = null,
) {
    /** Sovrappone i campi non vuoti di [other] (modifiche manuali) a questi. */
    fun overlaid(other: ComicMetadata?): ComicMetadata {
        if (other == null) return this
        fun pick(v: String?) = v?.takeIf { it.isNotBlank() }
        return ComicMetadata(
            title = pick(other.title) ?: title,
            series = pick(other.series) ?: series,
            number = pick(other.number) ?: number,
            volume = pick(other.volume) ?: volume,
            writer = pick(other.writer) ?: writer,
            penciller = pick(other.penciller) ?: penciller,
            summary = pick(other.summary) ?: summary,
            year = other.year ?: year,
            pageCount = other.pageCount ?: pageCount,
            languageISO = pick(other.languageISO) ?: languageISO,
            manga = other.manga ?: manga,
        )
    }

    companion object {
        val EMPTY = ComicMetadata()
    }
}

/**
 * Un file leggibile della libreria. L'identità è il percorso relativo alla cartella principale:
 * è la chiave di progressi, segnalibri, cache e sincronizzazione (uguale all'iPad).
 */
data class ComicFile(
    val relativePath: String,
    val fileName: String,
    val format: ComicFormat,
    val fileSizeBytes: Long,
    val modifiedMs: Long,
    val uri: Uri,
) {
    val id: String get() = relativePath
    val fileTitle: String get() = fileName.substringBeforeLast('.', fileName)
    val folderPath: String get() = relativePath.substringBeforeLast('/', "")
    val modificationStamp: Long get() = modifiedMs / 1000
}

/** Nodo dell'albero delle cartelle, costruito a ogni scansione e poi solo letto. */
class ComicFolder(
    val relativePath: String,
    val name: String,
    val depth: Int,
    var parent: ComicFolder? = null,
) {
    val subfolders: MutableList<ComicFolder> = mutableListOf()
    val comics: MutableList<ComicFile> = mutableListOf()

    val id: String get() = if (relativePath.isEmpty()) "__root__" else relativePath
    val isRoot: Boolean get() = parent == null

    override fun equals(other: Any?): Boolean = other is ComicFolder && other.id == id
    override fun hashCode(): Int = id.hashCode()

    val totalComicCount: Int by lazy { comics.size + subfolders.sumOf { it.totalComicCount } }

    fun allComicsRecursive(): List<ComicFile> = comics + subfolders.flatMap { it.allComicsRecursive() }

    fun allFoldersRecursive(): List<ComicFolder> = subfolders + subfolders.flatMap { it.allFoldersRecursive() }

    /** I primi [limit] fumetti scendendo nell'albero (anteprime delle cartelle). */
    fun previewComics(limit: Int): List<ComicFile> {
        val result = mutableListOf<ComicFile>()
        fun walk(f: ComicFolder) {
            for (c in f.comics) {
                if (result.size >= limit) return
                result.add(c)
            }
            for (s in f.subfolders) {
                if (result.size >= limit) return
                walk(s)
            }
        }
        walk(this)
        return result
    }

    /** Catena dalla radice a questa cartella (inclusa). */
    val ancestry: List<ComicFolder>
        get() {
            val chain = ArrayDeque<ComicFolder>()
            var node: ComicFolder? = this
            while (node != null) {
                chain.addFirst(node)
                node = node.parent
            }
            return chain.toList()
        }

    fun find(path: String): ComicFolder? {
        if (relativePath == path) return this
        for (sub in subfolders) {
            if (path == sub.relativePath || path.startsWith(sub.relativePath + "/")) {
                sub.find(path)?.let { return it }
            }
        }
        return null
    }

    /** Copia filtrata per la modalità Incognito: niente nascosti, niente rami vuoti. */
    fun pruned(hidden: Set<String>, newParent: ComicFolder? = null): ComicFolder? {
        if (newParent != null && hidden.contains(relativePath)) return null
        val copy = ComicFolder(relativePath, name, depth, newParent)
        copy.comics.addAll(comics.filter { !hidden.contains(it.relativePath) })
        subfolders.forEach { sub -> sub.pruned(hidden, copy)?.let { copy.subfolders.add(it) } }
        if (newParent != null && copy.comics.isEmpty() && copy.subfolders.isEmpty()) return null
        return copy
    }
}

// ---------------------------------------------------------------------------------------------
// Progressi, segnalibri, sessioni, evidenziazioni (stessi campi del JSON dell'iPad)

data class ReadingProgress(
    val relativePath: String,
    val currentPage: Int,
    val totalPages: Int,
    /** Secondi di lettura attiva. */
    val totalReadingTime: Double,
    val sessionCount: Int,
    val firstOpenedAt: Double? = null,
    val lastReadAt: Double? = null,
    val finishedAt: Double? = null,
    val isCompleted: Boolean = false,
    val modifiedAt: Double? = null,
) {
    /** Momento usato per confrontare due versioni dello stesso progresso. */
    val syncStamp: Double get() = modifiedAt ?: lastReadAt ?: firstOpenedAt ?: 0.0

    val fraction: Double
        get() {
            if (isCompleted) return 1.0
            if (totalPages <= 0) return 0.0
            return ((currentPage + 1).toDouble() / totalPages).coerceIn(0.0, 1.0)
        }

    /** Secondi dalla prima apertura al completamento. */
    val timeToFinish: Double?
        get() = if (finishedAt != null && firstOpenedAt != null) (finishedAt - firstOpenedAt) / 1000.0 else null

    companion object {
        fun new(path: String, totalPages: Int) = ReadingProgress(path, 0, totalPages, 0.0, 0)
    }
}

data class PageBookmark(
    val id: String,
    val relativePath: String,
    val pageIndex: Int,
    val createdAt: Double,
)

data class ReadingSession(
    val id: String,
    val relativePath: String,
    val title: String,
    val startedAt: Double,
    /** Secondi. */
    val duration: Double,
    val pagesRead: Int,
)

data class EpubHighlight(
    val id: String,
    val path: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: HighlightColor,
    val createdAt: Double,
    val modifiedAt: Double? = null,
) {
    val syncStamp: Double get() = modifiedAt ?: createdAt
}

/** "2h 14min", "45min", "3g 1h", "< 1min" (da secondi). */
fun readingDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).toInt()
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}g ${hours}h"
        hours > 0 -> "${hours}h ${minutes}min"
        minutes > 0 -> "${minutes}min"
        else -> "< 1min"
    }
}

/** Confronto "naturale" come localizedStandardCompare: Volume 2 prima di Volume 10. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
