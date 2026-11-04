package com.memora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.memora.app.ui.MemoraApp
import com.memora.app.ui.MemoraTheme
import com.memora.app.ui.MemoraViewModel

class MainActivity : ComponentActivity() {
    private val vm: MemoraViewModel by viewModels()
    private val ui by lazy { com.memora.app.ui.UiSettings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MemoraTheme(ui) { MemoraApp(vm) } }
    }

    /** Frees the on-device model's memory when the system is short of it; it reloads on the next question. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) vm.onLowMemory()
    }
}
