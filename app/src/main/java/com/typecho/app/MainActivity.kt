package com.typecho.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.typecho.app.ui.navigation.TypechoNavHost
import com.typecho.app.ui.theme.TypechoTheme

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { (application as TypechoApplication).container }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val darkMode by appContainer.settingsRepository.darkMode.collectAsState(initial = "system")
            val fontScale by appContainer.settingsRepository.fontScale.collectAsState(initial = 1.0f)

            val useDarkTheme = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            TypechoTheme(darkTheme = useDarkTheme, fontScale = fontScale) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TypechoNavHost(appContainer = appContainer)
                }
            }
        }
    }
}
