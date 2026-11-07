package com.remedicord.data.db

import com.remedicord.core.security.DbKeyManager
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val c = Converters()

    @Test fun bigDecimalRoundTripIsExact() {
        listOf("0.5", "2.50", "10", "0.125", "1000000.0001").forEach {
            val bd = BigDecimal(it)
            assertEquals(bd, c.stringToBigDecimal(c.bigDecimalToString(bd)))
        }
        assertNull(c.bigDecimalToString(null))
        assertNull(c.stringToBigDecimal(null))
    }

    @Test fun stringListRoundTripHandlesSpecialCharacters() {
        val sites = listOf("Abdomen L", "Thigh \"R\"", "Arm, upper", "", "ünïcødé ✓")
        assertEquals(sites, c.jsonToStringList(c.stringListToJson(sites)))
        assertNull(c.stringListToJson(null))
    }

    @Test fun entitiesSerializeForAuditAndBackup() {
        val med = MedicationEntity(name = "Amoxicillin", type = MedicationType.PILL, enabledSites = listOf("x"), createdAt = 1, updatedAt = 2)
        val json = AuditLogger.AuditJson
        assertEquals(med, json.decodeFromString(MedicationEntity.serializer(), json.encodeToString(MedicationEntity.serializer(), med)))
        val reg = RegimenEntity(medicationId = 1, effectiveFrom = 0, doseAmount = BigDecimal("2.50"), doseUnit = DoseUnit.ML, scheduleRuleJson = "{}", changeReason = "Initial", createdAt = 0)
        val text = json.encodeToString(RegimenEntity.serializer(), reg)
        assertTrue(text.contains("\"doseAmount\":\"2.50\""))
        assertEquals(reg, json.decodeFromString(RegimenEntity.serializer(), text))
    }

    @Test fun sqlCipherRawKeyFormat() {
        val raw = ByteArray(32) { it.toByte() }
        val key = String(DbKeyManager.toSqlCipherRawKey(raw), Charsets.US_ASCII)
        assertEquals("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'", key)
        val high = String(DbKeyManager.toSqlCipherRawKey(ByteArray(32) { 0xFF.toByte() }), Charsets.US_ASCII)
        assertEquals("x'" + "ff".repeat(32) + "'", high)
    }
}

/** PLAN.md §6: an app update must never wipe data. Fails the build if destructive fallback is ever added. */
class NoDestructiveMigrationTest {
    @Test fun noDestructiveMigrationFallbackAnywhereInAppCode() {
        val src = File("src/main")
        assertTrue("run from the app module: ${src.absolutePath}", src.isDirectory)
        val offenders = src.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { it.readText().contains("fallbackToDestructiveMigration") }
            .map { it.path }
            .toList()
        assertTrue("Destructive migration found in: $offenders", offenders.isEmpty())
    }
}
