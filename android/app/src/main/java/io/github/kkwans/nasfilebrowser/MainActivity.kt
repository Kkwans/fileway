package io.github.kkwans.nasfilebrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.ui.ClientApp
import io.github.kkwans.nasfilebrowser.ui.ClientTheme

class MainActivity : ComponentActivity() {
    private val model: ClientModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClientTheme { ClientApp(model) }
        }
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() { model.foreground(false); model.pausePlayback(); super.onStop() }
}
