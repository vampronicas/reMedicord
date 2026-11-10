package com.remedicord.ui

import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.StopReason
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4 change history wording (built from the audit trail). */
class ChangeHistoryTest {
    private val json = AuditLogger.AuditJson
    private val zone = ZoneOffset.UTC
    private val base = MedicationEntity(id = 1, name = "Amoxicillin", type = MedicationType.PILL, createdAt = 0, updatedAt = 0)
    private fun m(e: MedicationEntity) = json.encodeToString(MedicationEntity.serializer(), e)
    private fun r(e: RegimenEntity) = json.encodeToString(RegimenEntity.serializer(), e)
    private fun audit(id: Long, type: String, action: AuditAction, before: String?, after: String?, at: Long, note: String? = null) =
        AuditLogEntity(id, type, 1, action, before, after, at, note)
    private val rule1 = ScheduleRule.toJson(ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))))
    private val rule2 = ScheduleRule.toJson(ScheduleRule.FixedTimes(listOf(LocalTime.of(9, 0))))
    private fun reg(id: Long, amount: String, json: String, from: Long, reason: String) =
        RegimenEntity(id, 1, from, null, BigDecimal(amount), DoseUnit.MG, json, reason, from)

    @Test fun doseQuantityAndScheduleChanges() {
        val regs = listOf(
            audit(10, AuditLogger.REGIMEN, AuditAction.CREATE, null, r(reg(1, "500", rule1, 1000, "Initial")), 1000),
            audit(11, AuditLogger.REGIMEN, AuditAction.CREATE, null, r(reg(2, "250", rule1, 5000, "Doctor reduced")), 5000),
            audit(12, AuditLogger.REGIMEN, AuditAction.CREATE, null, r(reg(3, "250", rule2, 9000, "Once a day now")), 9000),
        )
        val h = ChangeHistory.build(emptyList(), regs, zone)
        assertEquals(listOf("Schedule changed: 08:00, 20:00 daily → 09:00 daily", "Dose changed: 500 mg → 250 mg", "Dose set: 500 mg"), h.map { it.title })
        assertEquals("Doctor reduced", h[1].detail)
        assertTrue(h[2].detail!!.contains("08:00"))
    }

    @Test fun statusChangesAndDetailEdits() {
        val paused = base.copy(status = MedicationStatus.PAUSED, pausedUntil = 86_400_000L * 3, pauseReason = "Travelling")
        val renamed = base.copy(name = "Amoxicillin 250", purposeNote = "Throat")
        val replaced = base.copy(status = MedicationStatus.STOPPED, stopReason = StopReason.REPLACED, stopNote = "Replaced by Amoxiclav", replacedById = 2)
        val meds = listOf(
            audit(1, AuditLogger.MEDICATION, AuditAction.CREATE, null, m(base), 0),
            audit(2, AuditLogger.MEDICATION, AuditAction.UPDATE, m(base), m(paused), 100),
            audit(3, AuditLogger.MEDICATION, AuditAction.UPDATE, m(paused), m(base), 200, "Pause ended: resumed automatically"),
            audit(4, AuditLogger.MEDICATION, AuditAction.UPDATE, m(base), m(renamed), 300),
            audit(5, AuditLogger.MEDICATION, AuditAction.UPDATE, m(base), m(base.copy(updatedAt = 9)), 350), // no visible change → hidden
            audit(6, AuditLogger.MEDICATION, AuditAction.UPDATE, m(base), m(replaced), 400),
        )
        val h = ChangeHistory.build(meds, emptyList(), zone)
        assertEquals(listOf("Replaced", "Name: Amoxicillin → Amoxicillin 250", "Resumed automatically", "Paused", "Medicine added"), h.map { it.title })
        assertEquals("Replaced by Amoxiclav", h[0].detail)
        assertTrue(h[1].detail!!.contains("Throat"))
        assertTrue(h[3].detail!!.contains("Travelling"))
    }

    @Test fun replacementEntryIsMarked() {
        val h = ChangeHistory.build(listOf(audit(1, AuditLogger.MEDICATION, AuditAction.CREATE, null, m(base.copy(replacesId = 7)), 0)), emptyList(), zone)
        assertEquals("Added as a replacement", h.single().title)
        val up = ChangeHistory.build(listOf(audit(1, AuditLogger.MEDICATION, AuditAction.CREATE, null,
            m(base.copy(replacesId = 7, replaceKind = com.remedicord.domain.model.ReplaceKind.UPGRADE)), 0)), emptyList(), zone)
        assertEquals("Added as an upgrade", up.single().title)
        val oldUp = base.copy(status = MedicationStatus.STOPPED, stopReason = StopReason.REPLACED, stopNote = "Upgraded to Amoxicillin 500", replacedById = 2)
        assertEquals("Upgraded", ChangeHistory.build(listOf(audit(2, AuditLogger.MEDICATION, AuditAction.UPDATE, m(base), m(oldUp), 5)), emptyList(), zone).single().title)
    }
}
