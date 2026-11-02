package com.usetracker.screentime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.usetracker.screentime.data.SyncWorker
import com.usetracker.screentime.ui.ScreenTimeApp
import com.usetracker.screentime.ui.ScreenTimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.schedule(this)
        setContent {
            ScreenTimeTheme {
                ScreenTimeApp()
            }
        }
    }
}
