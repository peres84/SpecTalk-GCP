package com.spectalk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spectalk.app.navigation.SpecTalkNavGraph
import com.spectalk.app.ui.theme.SpecTalkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpecTalkTheme {
                SpecTalkNavGraph()
            }
        }
    }
}
