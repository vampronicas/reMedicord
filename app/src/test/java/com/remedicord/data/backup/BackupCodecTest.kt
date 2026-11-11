package com.remedicord.data.backup

import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SettingEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val rule = ScheduleRule.toJson(ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0))))
    private val snap = BackupSnapshot(
        settings = listOf(SettingEntity("reminder.snoozeMinutes", "10"), SettingEntity("private.pin.hash", "SECRET"), SettingEntity("planner.plannedUntil", "1"),
            SettingEntity("reminder.sound", "BELL"), SettingEntity("lock.timeoutMinutes", "5"), SettingEntity("backup.reminderDays", "14"), SettingEntity("backup.lastAt", "99")),
        medications = listOf(MedicationEntity(id = 1, name = "=HYPERLINK(\"evil\")", type = MedicationType.EYE_DROP, enabledSites = listOf("a"), createdAt = 1, updatedAt = 1)),
        regimens = listOf(RegimenEntity(id = 1, medicationId = 1, effectiveFrom = 0, doseAmount = BigDecimal("2.5"), doseUnit = DoseUnit.DROP, scheduleRuleJson = rule, changeReason = "Initial", createdAt = 0)),
        doses = listOf(DoseEventEntity(id = 1, medicationId = 1, regimenId = 1, scheduledAt = 1_000, status = DoseStatus.TAKEN, takenAt = 1_000, note = "+1 \"quoted\"", createdAt = 0, updatedAt = 0)),
        skipRules = listOf(SkipRuleEntity(id = 1, medicationId = 1, mode = SkipMode.DAYS, count = 2, startAt = 0, reason = "Felt unwell", createdAt = 0)),
        audit = listOf(AuditLogEntity(id = 1, entityType = "medication", entityId = 1, action = AuditAction.CREATE, afterJson = "{}", at = 0)),
    )

    private fun files(s: BackupSnapshot = snap) = BackupCodec.encode(s, "0.1.0", 1, 123L, ZoneOffset.UTC)
    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { BackupCodec.writeZip(files, it) }.toByteArray()
    private fun roundTrip(bytes: ByteArray) = BackupCodec.decode(BackupCodec.readZip(ByteArrayInputStream(bytes)), 1)
    private fun rejects(bytes: ByteArray, contains: String) {
        val e = assertThrows(BackupException::class.java) { roundTrip(bytes) }
        assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
    }

    /** Rebuilds a valid backup after replacing one file (recomputes checksums so only that change is tested). */
    private fun withFile(name: String, content: String): ByteArray {
        val f = files().toMutableMap()
        f[name] = content.toByteArray()
        val m = BackupCodec.readManifest(f)
        f[BackupCodec.MANIFEST] = kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), m.copy(sha256 = f.filterKeys { it != BackupCodec.MANIFEST }.mapValues { BackupCodec.sha256(it.value) })).toByteArray()
        return zip(f)
    }

    @Test fun roundTripIsLosslessAndExcludesDeviceSettings() {
        val (m, s) = roundTrip(zip(files()))
        assertEquals(123L, m.exportedAt)
        // App settings travel with the backup; PIN, planner state and backup bookkeeping stay on the phone.
        assertEquals(listOf("reminder.snoozeMinutes", "reminder.sound", "lock.timeoutMinutes", "backup.reminderDays"), s.settings.map { it.key })
        assertEquals(snap.medications, s.medications); assertEquals(snap.regimens, s.regimens); assertEquals(snap.doses, s.doses)
        assertEquals(snap.skipRules, s.skipRules); assertEquals(snap.audit, s.audit)
        assertFalse(String(zip(files())).contains("SECRET"))
    }

    @Test fun csvIsFormulaSafe() {
        val csv = String(files().getValue(BackupCodec.CSV))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"evil\"\")\""))
        assertTrue(csv.contains("\"'+1 \"\"quoted\"\"\""))
        listOf("=1", "+1", "-1", "@SUM", "\tx", "\rx").forEach { assertTrue(BackupCodec.csvCell(it).startsWith("\"'")) }
        assertEquals("\"plain\"", BackupCodec.csvCell("plain"))
    }

    @Test fun garbageAndEmptyAreRejected() {
        rejects("not a zip".toByteArray(), "not a valid")
        rejects(ByteArray(0), "not a valid")
    }

    @Test fun unknownFilePathTraversalAndDuplicatesAreRejected() {
        rejects(zip(files() + ("../../evil.so" to ByteArray(1))), "Unexpected file")
        rejects(zip(files() + ("extra.json" to ByteArray(1))), "Unexpected file")
        // Java's ZipOutputStream refuses duplicates, so craft one by hand: write "manifest.jsoX", then patch
        // its name bytes to "manifest.json" (same length), as an attacker could.
        val raw = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                listOf(BackupCodec.MANIFEST, "manifest.jsoX").forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() }
            }
        }.toByteArray()
        val dup = String(raw, Charsets.ISO_8859_1).replace("manifest.jsoX", BackupCodec.MANIFEST).toByteArray(Charsets.ISO_8859_1)
        rejects(dup, "Duplicate")
    }

    @Test fun zipBombIsStoppedByActualDecompressedSize() {
        val bomb = zip(files() + (BackupCodec.CSV to ByteArray(5_000_000)))
        assertTrue(bomb.size < 100_000) // compresses tiny
        assertThrows(BackupException::class.java) {
            BackupCodec.readZip(ByteArrayInputStream(bomb), BackupCodec.Limits(maxEntryBytes = 1_000_000))
        }
    }

    @Test fun checksumMismatchIsDetected() {
        val f = files().toMutableMap()
        f[BackupCodec.MEDICATIONS] = f.getValue(BackupCodec.MEDICATIONS).copyOf().also { it[5] = 'X'.code.toByte() }
        rejects(zip(f), "damaged")
        rejects(zip(files() - BackupCodec.DOSES), "incomplete")
        rejects(zip(files() - BackupCodec.MANIFEST), "manifest")
    }

    @Test fun unknownFieldsAndEnumsAreRejected() {
        rejects(withFile(BackupCodec.SETTINGS, """[{"key":"a","value":"b","evil":1}]"""), "invalid")
        rejects(withFile(BackupCodec.MEDICATIONS, String(files().getValue(BackupCodec.MEDICATIONS)).replace("EYE_DROP", "ROOTKIT")), "invalid")
    }

    /** Same number of settings as the manifest says, so only the key/value check can reject it. */
    private fun withSettings(from: String, to: String): ByteArray {
        val s = String(files().getValue(BackupCodec.SETTINGS))
        assertTrue("fixture must contain $from", s.contains(from))
        return withFile(BackupCodec.SETTINGS, s.replace(from, to))
    }

    @Test fun privateSettingsCantBeSmuggledIn() {
        rejects(withSettings("\"key\":\"reminder.sound\",\"value\":\"BELL\"", "\"key\":\"private.pin.hash\",\"value\":\"x\""), "setting key")
        rejects(withSettings("\"key\":\"reminder.sound\"", "\"key\":\"planner.plannedUntil\""), "setting key")
    }

    @Test fun unknownSettingsAndOutOfRangeValuesAreRejected() {
        rejects(withSettings("\"key\":\"reminder.sound\"", "\"key\":\"reminder.somethingNew\""), "setting key")
        // A crafted backup must not be able to switch off the app lock (huge timeout) or break reminders.
        rejects(withSettings("\"key\":\"lock.timeoutMinutes\",\"value\":\"5\"", "\"key\":\"lock.timeoutMinutes\",\"value\":\"99999999\""), "setting value")
        rejects(withSettings("\"key\":\"lock.timeoutMinutes\",\"value\":\"5\"", "\"key\":\"lock.timeoutMinutes\",\"value\":\"-1\""), "setting value")
        rejects(withSettings("\"value\":\"10\"", "\"value\":\"0\""), "setting value") // snooze 0 min
        rejects(withSettings("\"value\":\"BELL\"", "\"value\":\"../../evil\""), "setting value")
        rejects(withSettings("\"value\":\"14\"", "\"value\":\"3650\""), "setting value")
    }

    @Test fun everyOfferedSettingChoiceIsAccepted() {
        listOf(0, 1, 5, 15).forEach { assertTrue(BackupCodec.isValidSetting("lock.timeoutMinutes", "$it")) }
        listOf(0, 7, 14, 30).forEach { assertTrue(BackupCodec.isValidSetting("backup.reminderDays", "$it")) }
        listOf(5, 10, 15, 30).forEach { assertTrue(BackupCodec.isValidSetting("reminder.snoozeMinutes", "$it")) }
        listOf(1, 2, 3, 5).forEach { assertTrue(BackupCodec.isValidSetting("reminder.maxSnoozes", "$it")); assertTrue(BackupCodec.isValidSetting("reminder.maxRepeats", "$it")) }
        listOf(5, 10, 15).forEach { assertTrue(BackupCodec.isValidSetting("reminder.repeatMinutes", "$it")) }
        listOf(3, 5, 10).forEach { assertTrue(BackupCodec.isValidSetting("reminder.eyeDropGapMinutes", "$it")) }
        listOf("true", "false").forEach { assertTrue(BackupCodec.isValidSetting("reminder.fullScreen", it)) }
        listOf("CHIME", "BEEP", "BELL", "DEFAULT", "SILENT").forEach { assertTrue(BackupCodec.isValidSetting("reminder.sound", it)) }
        assertFalse(BackupCodec.isValidSetting("reminder.fullScreen", "yes"))
        assertFalse(BackupCodec.isValidSetting("lock.timeoutMinutes", "2"))
    }

    @Test fun invalidSettingValuesAreNotExported() {
        val bad = snap.copy(settings = snap.settings + SettingEntity("reminder.repeatMinutes", "abc"))
        val (_, s) = roundTrip(zip(files(bad)))
        assertFalse(s.settings.any { it.key == "reminder.repeatMinutes" })
    }

    @Test fun changeHistoryTextIsCheckedLikeEveryOtherField() {
        val audit = String(files().getValue(BackupCodec.AUDIT))
        assertTrue(audit.contains("\"afterJson\":\"{}\""))
        // A medicine name hidden inside the change history, disguised with a right-to-left override (U+202E).
        rejects(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", """"afterJson":"{\"name\":\"\\u202eevil\"}"""")), "audit details")
        rejects(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", """"afterJson":"{\"bad\\u0007key\":1}"""")), "audit details")
        rejects(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", "\"afterJson\":\"not json\"")), "audit details")
        // Absurdly deep nesting is refused cleanly (a normal "invalid backup" message, never a crash).
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        rejects(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", "\"afterJson\":\"$deep\"")), "audit details")
        // Ordinary nesting is fine.
        val nested = "[".repeat(50) + "]".repeat(50)
        val (_, ok) = roundTrip(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", "\"afterJson\":\"$nested\"")))
        assertEquals(1, ok.audit.size)
        // Normal text with line breaks is fine.
        roundTrip(withFile(BackupCodec.AUDIT, audit.replace("\"afterJson\":\"{}\"", """"afterJson":"{\"note\":\"line 1\\nline 2\"}"""")))
    }

    @Test fun rowValidation() {
        val meds = String(files().getValue(BackupCodec.MEDICATIONS))
        rejects(withFile(BackupCodec.MEDICATIONS, meds.replace("\"name\":\"=HYPERLINK(\\\"evil\\\")\"", "\"name\":\"bad\\u0000name\"")), "medication name")
        val regs = String(files().getValue(BackupCodec.REGIMENS))
        rejects(withFile(BackupCodec.REGIMENS, regs.replace("\"medicationId\":1", "\"medicationId\":99")), "regimen without medication")
        rejects(withFile(BackupCodec.REGIMENS, regs.replace("\"2.5\"", "\"-1\"")), "dose amount")
        rejects(withFile(BackupCodec.REGIMENS, regs.replace("fixed", "unknown_kind")), "schedule")
    }

    @Test fun countsMustMatchManifest() {
        val f = files().toMutableMap()
        val m = BackupCodec.readManifest(f)
        f[BackupCodec.MANIFEST] = kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), m.copy(counts = m.counts + ("doses" to 5))).toByteArray()
        rejects(zip(f), "counts")
    }

    @Test fun newerVersionsAreRejected() {
        val f = files().toMutableMap()
        val m = BackupCodec.readManifest(f)
        f[BackupCodec.MANIFEST] = kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), m.copy(schemaVersion = 99)).toByteArray()
        rejects(zip(f), "newer version")
        f[BackupCodec.MANIFEST] = kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), m.copy(format = "other")).toByteArray()
        rejects(zip(f), "not a reMedicord backup")
    }
}
