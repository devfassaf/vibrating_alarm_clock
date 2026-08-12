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
        // Opening the app is a safe moment to reconcile the schedule with what is
        // actually armed — unlike process start, which can race an incoming trigger.
        AppGraph.syncSchedule()
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
