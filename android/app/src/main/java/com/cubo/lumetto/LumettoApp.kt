package com.cubo.lumetto

import android.app.Application
import com.cubo.lumetto.data.AppContext
import com.cubo.lumetto.data.AppSettings
import com.cubo.lumetto.data.FileAccess
import com.cubo.lumetto.data.HighlightStore
import com.cubo.lumetto.data.IncognitoStore
import com.cubo.lumetto.data.MetadataStore
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.data.TombstoneStore
import com.cubo.lumetto.sync.SyncManager

class LumettoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        AppSettings.load()
        TombstoneStore.load()
        ProgressStore.load()
        MetadataStore.load()
        HighlightStore.load()
        IncognitoStore.load()
        FileAccess.load()
        SyncManager.init(this)
    }
}
