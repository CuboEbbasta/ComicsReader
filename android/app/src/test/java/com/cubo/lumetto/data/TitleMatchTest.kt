package com.cubo.lumetto.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleMatchTest {
    private val titles = listOf("The Amazing Spider-Man 12", "Spider-Man", "Batman Year One", "Spider-Verse")

    @Test fun exactTitleWins() = assertEquals(1, TitleMatch.best("spider-man", titles))
    @Test fun prefixBeatsContains() = assertEquals(2, TitleMatch.best("batman", titles))
    @Test fun allWordsAnyOrder() = assertEquals(0, TitleMatch.best("amazing 12", titles))
    @Test fun caseAndSpacesIgnored() = assertEquals(3, TitleMatch.best("  SPIDER-VERSE ", titles))
    @Test fun noMatchOrEmpty() {
        assertEquals(-1, TitleMatch.best("superman", titles))
        assertEquals(-1, TitleMatch.best("", titles))
        assertEquals(-1, TitleMatch.best("x", emptyList()))
    }
}
