package com.remedicord

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remedicord.ui.navigation.ReMedicordNavHost
import com.remedicord.ui.theme.ReMedicordTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only exported component (home-screen launcher).
 * Security: it never reads the launching Intent's data or extras (PLAN.md §12.1).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReMedicordTheme {
                ReMedicordNavHost()
            }
        }
    }
}
