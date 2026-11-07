package com.remedicord.ui.history

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor() : ViewModel()

/** S9 — implemented in Phase 5. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun HistoryScreen(vm: HistoryViewModel) {
    Text("History", Modifier.padding(16.dp))
}
