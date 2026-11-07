package com.remedicord.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remedicord.R
import kotlinx.serialization.Serializable

/** Type-safe routes. Screens are added phase by phase (PLAN.md §8). */
@Serializable
object HomeRoute

@Composable
fun ReMedicordNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> { PlaceholderScreen() }
    }
}

@Composable
private fun PlaceholderScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.placeholder_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.placeholder_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
