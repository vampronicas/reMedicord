package com.remedicord.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.DoseActionService.ReviewAnswer
import com.remedicord.ui.Labels
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReviewViewModel @Inject constructor(
    doses: DoseRepository,
    meds: MedicationRepository,
    private val actions: DoseActionService,
) : ViewModel() {
    val groups: StateFlow<List<Pair<MedicationEntity, List<DoseEventEntity>>>?> =
        combine(doses.observeWithStatus(DoseStatus.UNCONFIRMED), meds.observeByStatus(*MedicationStatus.entries.toTypedArray())) { list, ms ->
            val byId = ms.associateBy { it.id }
            list.groupBy { it.medicationId }.mapNotNull { (id, ds) -> byId[id]?.let { it to ds.sortedBy { d -> d.scheduledAt } } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun answer(ids: List<Long>, a: ReviewAnswer) = viewModelScope.launch {
        ids.forEach { actions.review(it, a) }
        actions.finishReview()
    }
}

/** S14: doses that fell due while the app wasn't running (phone off / restore). Nothing is assumed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(vm: ReviewViewModel, onDone: () -> Unit) {
    val groups by vm.groups.collectAsState()
    LaunchedEffect(groups) { if (groups?.isEmpty() == true) onDone() }
    val g = groups ?: return
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Did you take these?", style = MaterialTheme.typography.titleLarge)
        Text("Answer now or later from Today. Doses not answered within 5 days are recorded as missed.", style = MaterialTheme.typography.bodySmall)
        val all = g.flatMap { it.second }.map { it.id }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.answer(all, ReviewAnswer.TAKEN) }) { Text("All taken") }
            OutlinedButton(onClick = { vm.answer(all, ReviewAnswer.MISSED) }) { Text("All missed") }
        }
        g.forEach { (med, ds) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(med.name + if (med.mustComplete) " (must complete)" else "", style = MaterialTheme.typography.titleSmall)
                    ds.forEach { d ->
                        Text(Labels.dateTime(Instant.ofEpochMilli(d.scheduledAt ?: 0)), style = MaterialTheme.typography.bodyLarge)
                        Text(Labels.pending(d.pendingReason) + " · counts as missed if not answered by " +
                            Labels.date(Instant.ofEpochMilli(d.scheduledAt ?: 0).plus(java.time.Duration.ofDays(com.remedicord.domain.policy.AmendPolicy.WINDOW_DAYS))),
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.answer(listOf(d.id), ReviewAnswer.TAKEN) }) { Text("Taken") }
                            OutlinedButton(onClick = { vm.answer(listOf(d.id), ReviewAnswer.MISSED) }) { Text("Missed") }
                            OutlinedButton(onClick = { vm.answer(listOf(d.id), ReviewAnswer.SKIPPED) }) { Text("Skipped") }
                        }
                    }
                }
            }
        }
    }
}

/** Decides whether to open the review prompt when the app is opened (after the PIN). */
@HiltViewModel
class ReviewPromptViewModel @Inject constructor(private val doses: DoseRepository) : ViewModel() {
    suspend fun hasPending(): Boolean = doses.countUnconfirmed() > 0
}
