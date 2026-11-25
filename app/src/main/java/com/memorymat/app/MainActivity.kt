package com.memorymat.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.memorymat.app.ui.components.SunnyBloomRevealContainer
import com.memorymat.app.ui.screens.HomeScreen
import com.memorymat.app.ui.theme.MemoryMatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isRecreated = savedInstanceState != null
        var readyToReveal by mutableStateOf(isRecreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        var splashIconDiameterPx by mutableStateOf<Float?>(null)

        if (!isRecreated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                val iconWidth = runCatching { splashScreenView.iconView?.width ?: 0 }.getOrDefault(0)
                if (iconWidth > 0) {
                    // ic_launcher_foreground draws VerySunny at 54/108 (50%) of the icon canvas
                    splashIconDiameterPx = iconWidth * (54f / 108f)
                }
                readyToReveal = true
                splashScreenView.remove()
            }
        }

        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            MemoryMatTheme {
                SunnyBloomRevealContainer(
                    readyToReveal = readyToReveal,
                    skipReveal = isRecreated,
                    initialIconDiameterPx = splashIconDiameterPx,
                ) {
                    HomeScreen()
                }
            }
        }
    }
}

