package com.cubo.lumetto.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cubo.lumetto.model.EpubFont
import com.cubo.lumetto.model.IncognitoAuthMethod
import com.cubo.lumetto.model.LibraryBackground
import com.cubo.lumetto.model.PageLayoutMode
import com.cubo.lumetto.model.PageTransitionStyle
import com.cubo.lumetto.model.ReadingDirection
import org.json.JSONObject

/** Preferenze dell'app, salvate a ogni modifica. Non si sincronizzano. */
object AppSettings {
    private val p get() = AppContext.prefs

    private var _maxDepth by mutableIntStateOf(10)
    var maxFolderDepth: Int
        get() = _maxDepth
        set(v) { _maxDepth = v; p.edit().putInt("settings.maxFolderDepth", v).apply() }

    private var _layout by mutableStateOf(PageLayoutMode.SINGLE)
    var pageLayout: PageLayoutMode
        get() = _layout
        set(v) { _layout = v; p.edit().putString("settings.pageLayout", v.raw).apply() }

    private var _transition by mutableStateOf(PageTransitionStyle.SLIDE)
    var transition: PageTransitionStyle
        get() = _transition
        set(v) { _transition = v; p.edit().putString("settings.transition", v.raw).apply() }

    private var _direction by mutableStateOf(ReadingDirection.AUTO)
    var readingDirection: ReadingDirection
        get() = _direction
        set(v) { _direction = v; p.edit().putString("settings.direction", v.raw).apply() }

    private var _background by mutableStateOf(LibraryBackground.BLACK)
    var libraryBackground: LibraryBackground
        get() = _background
        set(v) { _background = v; p.edit().putString("settings.background", v.raw).apply() }

    private var _epubFont by mutableStateOf(EpubFont.ORIGINAL)
    var epubFont: EpubFont
        get() = _epubFont
        set(v) { _epubFont = v; p.edit().putString("settings.epubFont", v.raw).apply() }

    private var _epubScale by mutableStateOf(1.0)
    var epubFontScale: Double
        get() = _epubScale
        set(v) { _epubScale = v; p.edit().putFloat("settings.epubFontScale", v.toFloat()).apply() }

    private var _authMethod by mutableStateOf(IncognitoAuthMethod.BIOMETRICS)
    var incognitoAuthMethod: IncognitoAuthMethod
        get() = _authMethod
        set(v) { _authMethod = v; p.edit().putString("settings.incognitoAuth", v.raw).apply() }

    private var _requiresAuth by mutableStateOf(true)
    var incognitoRequiresAuth: Boolean
        get() = _requiresAuth
        set(v) { _requiresAuth = v; p.edit().putBoolean("settings.incognitoRequiresAuth", v).apply() }

    private var _bgVersion by mutableIntStateOf(0)
    var backgroundImageVersion: Int
        get() = _bgVersion
        set(v) { _bgVersion = v; p.edit().putInt("settings.backgroundVersion", v).apply() }

    fun load() {
        _maxDepth = p.getInt("settings.maxFolderDepth", 10)
        _layout = PageLayoutMode.from(p.getString("settings.pageLayout", null)) ?: PageLayoutMode.SINGLE
        _transition = PageTransitionStyle.from(p.getString("settings.transition", null)) ?: PageTransitionStyle.SLIDE
        _direction = ReadingDirection.from(p.getString("settings.direction", null)) ?: ReadingDirection.AUTO
        _background = LibraryBackground.from(p.getString("settings.background", null)) ?: LibraryBackground.BLACK
        _epubFont = EpubFont.from(p.getString("settings.epubFont", null)) ?: EpubFont.ORIGINAL
        _epubScale = p.getFloat("settings.epubFontScale", 1f).toDouble()
        _authMethod = IncognitoAuthMethod.from(p.getString("settings.incognitoAuth", null)) ?: IncognitoAuthMethod.BIOMETRICS
        _requiresAuth = p.getBoolean("settings.incognitoRequiresAuth", true)
        _bgVersion = p.getInt("settings.backgroundVersion", 0)
    }
}

/** Preferenze del singolo fumetto (direzione, modalità, tema, posizione EPUB). */
object ComicPrefs {
    private const val KEY = "reader.comicPrefs.v1"
    private var cache: JSONObject? = null

    private fun all(): JSONObject {
        cache?.let { return it }
        val loaded = try {
            JSONObject(AppContext.prefs.getString(KEY, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        cache = loaded
        return loaded
    }

    fun value(field: String, path: String): String? {
        val entry = all().optJSONObject(path) ?: return null
        return if (entry.has(field)) entry.optString(field) else null
    }

    fun set(value: String?, field: String, path: String) {
        val root = all()
        val entry = root.optJSONObject(path) ?: JSONObject()
        if (value == null) entry.remove(field) else entry.put(field, value)
        if (entry.length() == 0) root.remove(path) else root.put(path, entry)
        AppContext.prefs.edit().putString(KEY, root.toString()).apply()
    }
}
