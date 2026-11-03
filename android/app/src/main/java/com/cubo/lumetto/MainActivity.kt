package com.cubo.lumetto

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.cubo.lumetto.data.ProgressStore
import com.cubo.lumetto.sync.SyncManager
import com.cubo.lumetto.ui.LumettoTheme
import com.cubo.lumetto.ui.RootScreen

/** AppCompatActivity (e quindi FragmentActivity) serve allo sblocco con impronta. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                SyncManager.sceneChanged(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                ProgressStore.flush()
                SyncManager.sceneChanged(false)
            }
        })
        setContent {
            LumettoTheme {
                RootScreen(this)
            }
        }
    }
}
