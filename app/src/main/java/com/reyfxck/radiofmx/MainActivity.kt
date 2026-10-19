package com.reyfxck.radiofmx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.reyfxck.radiofmx.ui.RadioFmxApp
import com.reyfxck.radiofmx.ui.theme.RadioFmxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadioFmxTheme {
                RadioFmxApp()
            }
        }
    }
}
