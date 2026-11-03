package com.cubo.lumetto.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.model.ComicFolder

/**
 * Navigazione spaziale universale, uguale a ogni profondità (come sull'iPad).
 *
 * Ogni cartella appartiene a una "fila" orizzontale di sorelle che si sfoglia come un carosello
 * (← / →, circolare). La fila più alta contiene la Home seguita dalle cartelle di primo livello.
 * ↓ entra nella prima sottocartella (nuova fila), ↑ torna al genitore.
 */
object LibraryNavigator {
    enum class Move { NONE, HORIZONTAL, DOWN, UP }

    var current by mutableStateOf<ComicFolder?>(null)
        private set
    var lastMove by mutableStateOf(Move.NONE)
        private set

    private const val KEY = "library.lastFolder"

    private fun setCurrent(folder: ComicFolder?) {
        current = folder
        folder?.let { AppContext.prefs.edit().putString(KEY, it.relativePath).apply() }
    }

    fun attach(root: ComicFolder?) {
        if (root == null) { current = null; return }
        val cur = current
        val saved = AppContext.prefs.getString(KEY, null)
        when {
            cur != null && root.find(cur.relativePath) != null -> current = root.find(cur.relativePath)
            cur == null && saved != null && root.find(saved) != null -> current = root.find(saved)
            else -> current = root
        }
    }

    fun row(folder: ComicFolder): List<ComicFolder> {
        val parent = folder.parent ?: return listOf(folder) + folder.subfolders
        if (parent.parent == null) return listOf(parent) + parent.subfolders
        return parent.subfolders
    }

    fun rowId(folder: ComicFolder): String {
        val parent = folder.parent
        if (parent == null || parent.parent == null) return "__top__"
        return parent.id
    }

    val currentRow: List<ComicFolder> get() = current?.let { row(it) } ?: emptyList()
    val currentRowId: String get() = current?.let { rowId(it) } ?: ""

    /** Vicini nella fila circolare. */
    fun neighbors(folder: ComicFolder): Pair<ComicFolder?, ComicFolder?> {
        val items = row(folder)
        val i = items.indexOf(folder)
        if (items.size <= 1 || i < 0) return null to null
        val n = items.size
        return items[(i - 1 + n) % n] to items[(i + 1) % n]
    }

    fun position(folder: ComicFolder): Pair<Int, Int> {
        val items = row(folder)
        return maxOf(0, items.indexOf(folder)) to items.size
    }

    val canGoUp: Boolean get() = current?.parent != null

    /** Il carosello ha già finito di scorrere: si aggiorna solo lo stato. */
    fun selectFromScroll(id: String) {
        val target = currentRow.firstOrNull { it.id == id } ?: return
        if (target == current) return
        lastMove = Move.HORIZONTAL
        setCurrent(target)
    }

    fun goLeft() { current?.let { c -> neighbors(c).first?.let { go(it) } } }
    fun goRight() { current?.let { c -> neighbors(c).second?.let { go(it) } } }

    fun goDown() {
        val c = current ?: return
        if (c.isRoot) { goRight(); return }
        c.subfolders.firstOrNull()?.let { go(it) }
    }

    fun goUp() { current?.parent?.let { go(it) } }

    fun jump(folder: ComicFolder) = go(folder)

    private fun go(target: ComicFolder) {
        val c = current ?: return
        if (target == c) return
        lastMove = when {
            rowId(target) == rowId(c) -> Move.HORIZONTAL
            c.relativePath.startsWith(target.relativePath) -> Move.UP
            else -> Move.DOWN
        }
        setCurrent(target)
    }
}
