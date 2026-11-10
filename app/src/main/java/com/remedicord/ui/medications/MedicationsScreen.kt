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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.ui.Labels
import com.remedicord.ui.components.MedIcon
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One row in the medicines list: dates for its tab, plus the upgrade / downgrade link (v1.4). */
data class MedRow(val med: MedicationEntity, val dates: String, val link: Pair<Long, String>?)

@HiltViewModel
class MedicationsViewModel @Inject constructor(
    private val repo: MedicationRepository,
    private val service: MedicationService,
    private val time: TimeProvider,
) : ViewModel() {
    private val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private fun d(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it).atZone(time.zone())) } ?: "?"

    val rows: StateFlow<List<MedRow>> = repo.observeByStatus(*MedicationStatus.entries.toTypedArray()).map { all ->
        val byId = all.associateBy { it.id }
        all.map { m -> MedRow(m, dates(m), link(m, byId)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun dates(m: MedicationEntity): String {
        val regs = repo.regimens(m.id)
        val start = d(regs.minOfOrNull { it.effectiveFrom } ?: m.createdAt)
        return when (m.status) {
            MedicationStatus.ACTIVE -> {
                val end = service.courseView(m)?.projectedEnd?.toEpochMilli()
                    ?: regs.takeIf { it.isNotEmpty() && it.all { r -> r.effectiveTo != null } }?.maxOf { it.effectiveTo!! }?.minus(1)
                "Started $start · " + (end?.let { "target end ${d(it)}" } ?: "no end date") +
                    if ((m.remindersMutedUntil ?: 0) > time.nowMillis()) " · reminders off today" else ""
            }
            MedicationStatus.PAUSED -> "Started $start · paused ${d(m.pausedAt)} · " +
                (m.pausedUntil?.let { "resumes ${d(it)}" } ?: "until you resume")
            MedicationStatus.STOPPED, MedicationStatus.COMPLETED -> "$start – ${d(m.stoppedAt ?: m.updatedAt)} · " + when {
                m.status == MedicationStatus.COMPLETED -> "Completed"
                m.stopReason == StopReason.REPLACED -> m.stopNote ?: "Replaced"
                else -> "Stopped" + (m.stopReason?.let { " (" + Labels.stopReason(it).lowercase() + ")" } ?: "")
            }
        }
    }

    /** "Upgraded from X" on the new entry, "Upgraded to Y" on the old one. */
    private fun link(m: MedicationEntity, byId: Map<Long, MedicationEntity>): Pair<Long, String>? {
        m.replacesId?.let { id -> byId[id]?.let { return id to "${Labels.replacedFrom(m.replaceKind)} ${it.name} ‹" } }
        m.replacedById?.let { id -> byId[id]?.let { return id to "${Labels.replacedTo(it.replaceKind)} ${it.name} ›" } }
        return null
    }
}

/** S3: medicine list with Active / Paused / Finished tabs, dates per tab, and upgrade / downgrade links. */
@Composable
fun MedicationsScreen(vm: MedicationsViewModel, onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val rows by vm.rows.collectAsState()
    val lists = listOf(
        rows.filter { it.med.status == MedicationStatus.ACTIVE },
        rows.filter { it.med.status == MedicationStatus.PAUSED },
        rows.filter { it.med.status == MedicationStatus.STOPPED || it.med.status == MedicationStatus.COMPLETED }.sortedByDescending { it.med.stoppedAt ?: it.med.updatedAt },
    )
    Scaffold(floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, text = { Text("+ Add medicine") }, icon = {}) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Active", "Paused", "Finished").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text("$t (${lists[i].size})") })
                }
            }
            OutlinedTextField(query, { if (it.length <= 40) query = it }, label = { Text("Search") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            val shown = lists[tab].filter { query.isBlank() || it.med.name.contains(query.trim(), ignoreCase = true) }
            if (shown.isEmpty()) Text(if (tab == 0) "No medicines yet. Tap \"Add medicine\"." else "Nothing here.", Modifier.padding(16.dp))
            LazyColumn {
                items(shown, key = { it.med.id }) { row ->
                    val m = row.med
                    ListItem(
                        leadingContent = { MedIcon(m.type, m.colorTag, 40) },
                        headlineContent = { Text(m.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Column {
                                Text(Labels.type(m.type) + " · " + Labels.purpose(m.purpose).substringBefore(" (") + if (m.mustComplete) " · must complete" else "")
                                Text(row.dates, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                row.link?.let { (id, text) ->
                                    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp).clickable { onOpen(id) })
                                }
                            }
                        },
                        trailingContent = {
                            if (m.status != MedicationStatus.ACTIVE) Badge(statusLabel(m), MaterialTheme.colorScheme.surfaceContainerHighest)
                            else if (m.mustComplete) Badge("Must complete", MaterialTheme.colorScheme.errorContainer)
                        },
                        modifier = Modifier.clickable { onOpen(m.id) },
                    )
                }
            }
        }
    }
}
