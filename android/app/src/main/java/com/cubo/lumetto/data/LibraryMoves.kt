package com.cubo.lumetto.data

/** Un fumetto cambia percorso (spostato o rinominato): tutto ciò che è legato al percorso lo segue. */
object LibraryMoves {
    fun migrate(old: String, new: String) {
        ProgressStore.rekey(old, new)
        MetadataStore.rekey(old, new)
        HighlightStore.rekey(old, new)
        IncognitoStore.rekey(old, new)
        ComicPrefs.rekey(old, new)
    }
}
