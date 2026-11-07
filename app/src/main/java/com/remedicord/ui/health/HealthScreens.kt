package com.remedicord.ui.health

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.reminders.ReminderHealthChecker
import com.remedicord.reminders.ReminderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val checker: ReminderHealthChecker,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _items = MutableStateFlow(checker.check())
    val items: StateFlow<List<ReminderHealthChecker.Item>> = _items
    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> = _onboarded

    init { viewModelScope.launch { _onboarded.value = settings.get(ReminderSettingsRepository.ONBOARDING_DONE) == "true" } }

    fun refresh() { _items.value = checker.check() }
    fun finishOnboarding() = viewModelScope.launch { settings.put(ReminderSettingsRepository.ONBOARDING_DONE, "true"); _onboarded.value = true }
}

/** The checklist used by both onboarding (S1) and the health screen (S13). */
@Composable
fun HealthChecklist(vm: HealthViewModel) {
    val items by vm.items.collectAsState()
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    items.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((if (item.ok) "✅ " else "❌ ") + item.title, style = MaterialTheme.typography.titleMedium)
                Text(item.why, style = MaterialTheme.typography.bodySmall)
                if (!item.ok) Button(onClick = {
                    if (item.key == ReminderHealthChecker.Key.NOTIFICATIONS && android.os.Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else item.fix?.let { runCatching { context.startActivity(it) } }
                }) { Text("Fix") }
            }
        }
    }
}

/** S1 onboarding (after the PIN is set): explains and requests each reminder requirement once. */
@Composable
fun OnboardingScreen(vm: HealthViewModel, onDone: () -> Unit) {
    val done by vm.onboarded.collectAsState()
    LaunchedEffect(done) { if (done == true) onDone() }
    if (done != false) return
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set up reminders", style = MaterialTheme.typography.headlineSmall)
        Text("reMedicord works fully offline. Your data never leaves this phone. To make sure reminders always ring on time, please allow the following:")
        HealthChecklist(vm)
        Button(onClick = { vm.finishOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        OutlinedButton(onClick = { vm.finishOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now (you can fix this later in Settings)") }
    }
}

/** S13 reminder health. */
@Composable
fun HealthScreen(vm: HealthViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reminder health", style = MaterialTheme.typography.headlineSmall)
        HealthChecklist(vm)
    }
}
