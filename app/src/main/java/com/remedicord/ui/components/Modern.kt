package com.remedicord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.ui.theme.StatusColors
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ icons (E8: small hand-picked set)

/** Simple line icons (24 dp grid), one per medicine type. No icon library needed. */
object MedIcons {
    private fun icon(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(pathData = addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    private val tablet = icon("tablet", "M4,12a8,8 0 1,0 16,0a8,8 0 1,0 -16,0", "M6.5,6.5l11,11")
    private val pill = icon("pill", "M10.5,3.5a5,5 0 0,1 7,7l-7,7a5,5 0 0,1 -7,-7z", "M7,7l7,7")
    private val liquid = icon("liquid", "M9,2h6v3h-6z", "M8,5h8l1,3v12a2,2 0 0,1 -2,2h-6a2,2 0 0,1 -2,-2v-12z", "M7,12h10")
    private val drop = icon("drop", "M12,3c0,0 6,6.6 6,11a6,6 0 0,1 -12,0c0,-4.4 6,-11 6,-11z")
    private val syringe = icon("syringe", "M18,2l4,4", "M15,5l4,4", "M3,21l4,-4", "M8,16l-2,-2l9,-9l4,4l-9,9z", "M11,9l2,2", "M9,11l2,2")
    private val inhaler = icon("inhaler", "M8,3h5v6h-5z", "M6,9h9a2,2 0 0,1 2,2v8a2,2 0 0,1 -2,2h-9a2,2 0 0,1 -2,-2v-8a2,2 0 0,1 2,-2z", "M17,14h3")
    private val spray = icon("spray", "M10,2h4v4h-4z", "M9,6h6l1,4v10a2,2 0 0,1 -2,2h-4a2,2 0 0,1 -2,-2v-10z")
    private val cream = icon("cream", "M6,9h12l-2,12h-8z", "M9,9v-3h6v3", "M10,3h4v3h-4z")
    private val patch = icon("patch", "M7,4h10a3,3 0 0,1 3,3v10a3,3 0 0,1 -3,3h-10a3,3 0 0,1 -3,-3v-10a3,3 0 0,1 3,-3z", "M9,9h6v6h-6z")
    private val bandage = icon("bandage", "M4.9,15.5l10.6,-10.6a2.8,2.8 0 0,1 4,4l-10.6,10.6a2.8,2.8 0 0,1 -4,-4z", "M10.5,10.5h0.01", "M13.5,13.5h0.01", "M12,12h0.01")

    fun of(t: MedicationType): ImageVector = when (t) {
        MedicationType.PILL -> pill
        MedicationType.LIQUID -> liquid
        MedicationType.EYE_DROP, MedicationType.EAR_DROP -> drop
        MedicationType.INJECTION -> syringe
        MedicationType.INHALER -> inhaler
        MedicationType.NASAL_SPRAY -> spray
        MedicationType.CREAM -> cream
        MedicationType.PATCH -> patch
        MedicationType.BANDAGE -> bandage
    }

    /** Tablets read better as a round tablet than a capsule when the unit is TABLET. */
    val roundTablet: ImageVector get() = tablet
}

/** 8 soft medicine colours (E8). Mid-tones that carry a white icon in both light and dark mode. */
object MedColors {
    val palette = listOf(
        Color(0xFF4F9D8F), Color(0xFFC0794B), Color(0xFF6F7FC9), Color(0xFFA3699C),
        Color(0xFF5B8FB9), Color(0xFF8A8F4A), Color(0xFFC46A7F), Color(0xFF7A8A99),
    )
    val names = listOf("Teal", "Amber", "Indigo", "Plum", "Blue", "Olive", "Rose", "Slate")
    fun of(index: Int?): Color = palette[((index ?: 0) % palette.size + palette.size) % palette.size]
}

@Composable
fun MedIcon(type: MedicationType, colorIndex: Int?, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(MedColors.of(colorIndex), RoundedCornerShape((size * 0.32f).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MedIcons.of(type), contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Colour picker for the medicine form. */
@Composable
fun ColorPicker(selected: Int?, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MedColors.palette.forEachIndexed { i, c ->
            val on = (selected ?: -1) == i
            Box(
                Modifier.size(34.dp).background(c, CircleShape)
                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onPick(i) }.semantics { contentDescription = MedColors.names[i] + if (on) ", selected" else "" },
            )
        }
    }
}

// ------------------------------------------------------------------ status

@Composable
fun StatusDot(status: DoseStatus, size: Int = 8) {
    val c = when (status) {
        DoseStatus.TAKEN -> Color(0xFF5AA864); DoseStatus.MISSED -> Color(0xFFD9675F); DoseStatus.UNCONFIRMED -> Color(0xFFE8A24A)
        DoseStatus.DUE, DoseStatus.SNOOZED -> Color(0xFF4F8BD1); DoseStatus.SKIPPED -> Color(0xFF9E9E9E)
        DoseStatus.PLANNED, DoseStatus.NOT_NEEDED -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(size.dp).background(c, CircleShape))
}

// ------------------------------------------------------------------ sections (E1)

/** Collapsible time-of-day section with a summary line visible when closed. */
@Composable
fun CollapsibleSection(
    title: String, summary: String, summaryStatus: DoseStatus?, startExpanded: Boolean, key: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(startExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "$title, $summary, ${if (expanded) "expanded" else "collapsed"}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                summaryStatus?.let { StatusDot(it); Spacer(Modifier.width(6.dp)) }
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("⌄", modifier = Modifier.rotate(rotation), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        }
    }
}

// ------------------------------------------------------------------ dose card (E5, E8)

data class DoseCardModel(
    val type: MedicationType,
    val colorIndex: Int?,
    val name: String,
    val line: String,
    val status: DoseStatus,
    val statusText: String,
)

/**
 * Dose card: icon + colour, name, details, status in words. Tapping the card or › opens the record sheet.
 * [quick] (today's due doses) adds Taken / Later / Skip buttons; [swipe] enables swipe right = Taken, left = Later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoseCard(
    m: DoseCardModel,
    onOpen: (() -> Unit)?,
    quick: (@Composable () -> Unit)? = null,
    onSwipeTaken: (() -> Unit)? = null,
    onSwipeLater: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val swipeable = onSwipeTaken != null || onSwipeLater != null
    val drag = if (!swipeable) Modifier else Modifier.pointerInput(Unit) {
        val threshold = size.width * 0.33f
        detectHorizontalDragGestures(
            onDragEnd = {
                val v = offset.value
                scope.launch {
                    when {
                        v > threshold && onSwipeTaken != null -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSwipeTaken() }
                        v < -threshold && onSwipeLater != null -> onSwipeLater()
                    }
                    offset.animateTo(0f)
                }
            },
            onDragCancel = { scope.launch { offset.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            scope.launch { offset.snapTo((offset.value + amount).coerceIn(-size.width * 0.5f, size.width * 0.5f)) }
        }
    }
    Box(Modifier.fillMaxWidth()) {
        if (swipeable && abs(offset.value) > 4f) {
            Row(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (offset.value > 0) Text("✓ Taken", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (offset.value < 0) Text("Later ⏱", fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp), color = StatusColors.container(m.status),
            modifier = Modifier.fillMaxWidth().offset { IntOffset(offset.value.roundToInt(), 0) }.then(drag)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        ) {
            Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MedIcon(m.type, m.colorIndex, 38)
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (m.line.isNotBlank()) Text(m.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 2.dp)) {
                            StatusDot(m.status)
                            Text(m.statusText, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (onOpen != null) TextButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = "Edit record" }) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
                quick?.let { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { it() } }
                if (swipeable) Text("Swipe right: Taken · Swipe left: Later", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ record sheet (E5)

/** What the user chose in the record sheet. */
data class RecordEdit(val status: DoseStatus, val takenTime: LocalTime?, val skipReason: String?, val site: String?, val note: String)

data class RecordSheetModel(
    val title: String,
    val subtitle: String,
    val type: MedicationType,
    val colorIndex: Int?,
    val current: DoseStatus,
    val takenTime: LocalTime?,
    val skipReason: String?,
    val site: String?,
    val siteChoices: List<String>,
    val note: String,
    val editable: Boolean,
    /** Today's not-yet-due or due dose: only Taken / Skipped make sense. */
    val upcoming: Boolean,
    val changes: Int,
)

/** E5: one sheet per intake record. Never opens the medicine page. Read-only outside the 5-day window. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordSheet(model: RecordSheetModel, onDismiss: () -> Unit, onSave: (RecordEdit) -> Unit) {
    val choices = if (model.upcoming) listOf(DoseStatus.TAKEN, DoseStatus.SKIPPED) else listOf(DoseStatus.TAKEN, DoseStatus.MISSED, DoseStatus.SKIPPED)
    var status by remember { mutableStateOf(model.current.takeIf { it in choices } ?: DoseStatus.TAKEN) }
    var time by remember { mutableStateOf(model.takenTime ?: LocalTime.now().withSecond(0).withNano(0)) }
    var reason by remember { mutableStateOf(model.skipReason) }
    var site by remember { mutableStateOf(model.site) }
    var note by remember { mutableStateOf(model.note) }
    var pickTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MedIcon(model.type, model.colorIndex, 44)
                Column {
                    Text(model.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(model.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = status == s, enabled = model.editable, onClick = { status = s },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    ) { Text(when (s) { DoseStatus.TAKEN -> "Taken"; DoseStatus.MISSED -> "Missed"; else -> "Skipped" }) }
                }
            }
            if (status == DoseStatus.TAKEN) OutlinedButton(onClick = { pickTime = true }, enabled = model.editable, modifier = Modifier.fillMaxWidth()) { Text("Taken at $time") }
            if (status == DoseStatus.SKIPPED) {
                Text("Reason", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(SkipPolicy.PRESET_REASONS, reason, { it }) { if (model.editable) reason = it }
            }
            if (model.siteChoices.isNotEmpty() && status == DoseStatus.TAKEN) {
                Text("Site", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(model.siteChoices, site, { it }) { if (model.editable) site = it }
            }
            OutlinedTextField(note, { if (it.length <= 500 && model.editable) note = it }, label = { Text("Note (optional)") }, enabled = model.editable, modifier = Modifier.fillMaxWidth())
            Text(
                if (model.editable) "You can change records for 5 days. Every change is kept (changed ${model.changes} time${if (model.changes == 1) "" else "s"})."
                else "Read-only: records older than 5 days can't be changed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text(if (model.editable) "Cancel" else "Close") }
                if (model.editable) Button(
                    enabled = status != DoseStatus.SKIPPED || reason != null,
                    onClick = { onSave(RecordEdit(status, time.takeIf { status == DoseStatus.TAKEN }, reason.takeIf { status == DoseStatus.SKIPPED }, site, note)) },
                ) { Text("Save record") }
            }
        }
    }
    if (pickTime) TimePickerDialog(time, { pickTime = false }) { time = it; pickTime = false }
}

// ------------------------------------------------------------------ compact medicine filter (E3)

data class FilterItem(val id: Long, val name: String, val type: MedicationType, val colorIndex: Int?, val active: Boolean)

/** One-line button; opens a searchable checklist. Empty selection = all medicines. */
@Composable
fun MedicineFilterButton(items: List<FilterItem>, selected: Set<Long>, onApply: (Set<Long>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).clickable { open = true },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected.isEmpty()) "Medicines: All (${items.size})" else "Medicines: ${selected.size} selected", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f)); Text("▾")
        }
    }
    if (open) MedicineFilterSheet(items, selected, onDismiss = { open = false }) { onApply(it); open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicineFilterSheet(items: List<FilterItem>, selected: Set<Long>, onDismiss: () -> Unit, onApply: (Set<Long>) -> Unit) {
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Medicines", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(query, { if (it.length <= 40) query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = { picked = items.map { it.id }.toSet() }) { Text("Select all") }; TextButton(onClick = { picked = emptySet() }) { Text("Clear") } }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                items.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .sortedWith(compareBy({ !it.active }, { it.name.lowercase() })).forEach { it ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (it.id in picked) picked - it.id else picked + it.id }.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = it.id in picked, onCheckedChange = null)
                            MedIcon(it.type, it.colorIndex, 28)
                            Text(it.name + if (it.active) "" else " (finished)")
                        }
                    }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = { onApply(picked) }) { Text("Apply") } }
        }
    }
}
