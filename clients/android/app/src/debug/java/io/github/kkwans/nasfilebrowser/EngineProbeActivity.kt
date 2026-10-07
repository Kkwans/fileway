package io.github.kkwans.nasfilebrowser

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/** Instrumentation host only. Does not construct ClientModel or a default engine. */
class EngineProbeActivity : Activity() {
    lateinit var viewport: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewport = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(viewport)
    }
}
