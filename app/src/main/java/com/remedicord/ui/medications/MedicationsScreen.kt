package com.remedicord.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.ui.Labels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MedicationsViewModel @Inject constructor(private val repo: MedicationRepository) : ViewModel() {
    private fun flow(vararg s: MedicationStatus): StateFlow<List<MedicationEntity>> =
        repo.observeByStatus(*s).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val active = flow(MedicationStatus.ACTIVE)
    val paused = flow(MedicationStatus.PAUSED)
    val finished = flow(MedicationStatus.STOPPED, MedicationStatus.COMPLETED)
}

/** S3: medication list with Active / Paused / Finished tabs and search. */
@Composable
fun MedicationsScreen(vm: MedicationsViewModel, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val lists = listOf(vm.active.collectAsState().value, vm.paused.collectAsState().value, vm.finished.collectAsState().value)
    Scaffold(floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, text = { Text("+ Add medicine") }, icon = {}) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Active", "Paused", "Finished").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text("$t (${lists[i].size})") })
                }
            }
            OutlinedTextField(query, { if (it.length <= 40) query = it }, label = { Text("Search") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            val shown = lists[tab].filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            if (shown.isEmpty()) Text(if (tab == 0) "No medicines yet. Tap \"Add medicine\"." else "Nothing here.", Modifier.padding(16.dp))
            LazyColumn {
                items(shown, key = { it.id }) { m ->
                    ListItem(
                        leadingContent = { com.remedicord.ui.components.MedIcon(m.type, m.colorTag, 40) },
                        headlineContent = { Text(m.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
                        trailingContent = { if (m.status != MedicationStatus.ACTIVE) Badge(statusLabel(m), MaterialTheme.colorScheme.surfaceContainerHighest) else if (m.mustComplete) Badge("Must complete", MaterialTheme.colorScheme.errorContainer) },
                        supportingContent = {
                            Text(Labels.type(m.type) + " · " + Labels.purpose(m.purpose).substringBefore(" (") + (if (m.mustComplete) " · must complete" else "") +
                                (m.stopReason?.let { " · " + Labels.stopReason(it) } ?: ""))
                        },
                        modifier = Modifier.clickable { onOpen(m.id) },
                    )
                }
            }
        }
    }
}
