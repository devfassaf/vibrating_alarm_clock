package com.faybish.vibealarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.faybish.vibealarm.ui.VibeAlarmApp
import com.faybish.vibealarm.ui.theme.VibeAlarmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeAlarmTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VibeAlarmApp()
                }
            }
        }
    }
}
