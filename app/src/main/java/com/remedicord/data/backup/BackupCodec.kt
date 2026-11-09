package com.remedicord.data.backup

import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SettingEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.usecase.FormValidator
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Reason shown to the user when a backup file is rejected. Nothing is changed when this is thrown. */
class BackupException(message: String) : Exception(message)

/** Everything a backup contains (PLAN.md §7). */
data class BackupSnapshot(
    val settings: List<SettingEntity>,
    val medications: List<MedicationEntity>,
    val regimens: List<RegimenEntity>,
    val doses: List<DoseEventEntity>,
    val skipRules: List<SkipRuleEntity>,
    val audit: List<AuditLogEntity>,
)

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val counts: Map<String, Int>,
    val sha256: Map<String, String>,
)

/**
 * Builds and reads reMedicord backup ZIPs. Pure Kotlin (no Android) so every safety check is unit-tested.
 *
 * Import safety (PLAN.md §7, §12.1): only known file names, no paths, no duplicates, hard limits on
 * entry count and on bytes actually decompressed (zip-bomb safe), SHA-256 must match the manifest
 * (detects corruption, not deliberate tampering), strict JSON (unknown fields / enum values rejected),
 * and every row validated (ranges, text rules, references between tables, schedule rules).
 */
object BackupCodec {
    const val FORMAT = "remedicord-backup"
    const val FORMAT_VERSION = 1
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val MEDICATIONS = "medications.json"
    const val REGIMENS = "regimens.json"
    const val DOSES = "dose_events.json"
    const val SKIP_RULES = "skip_rules.json"
    const val AUDIT = "audit_log.json"
    const val CSV = "history.csv"
    private val DATA_FILES = listOf(SETTINGS, MEDICATIONS, REGIMENS, DOSES, SKIP_RULES, AUDIT, CSV)
    val ALLOWED = setOf(MANIFEST) + DATA_FILES

    /** Settings that describe this phone, not the user's data: never exported, never imported. */
    private val DEVICE_PREFIXES = listOf("private.", "planner.", "onboarding.", "backup.")

    data class Limits(val maxEntryBytes: Long = 100L * 1024 * 1024, val maxTotalBytes: Long = 250L * 1024 * 1024, val maxRows: Int = 1_000_000)

    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val strict = Json { ignoreUnknownKeys = false; isLenient = false; encodeDefaults = true }

    fun isExportableSetting(key: String) = DEVICE_PREFIXES.none { key.startsWith(it) }

    // ---------------------------------------------------------------- export

    fun encode(s: BackupSnapshot, appVersion: String, schemaVersion: Int, exportedAt: Long, zone: ZoneId): Map<String, ByteArray> {
        val files = linkedMapOf(
            SETTINGS to enc(ListSerializer(SettingEntity.serializer()), s.settings.filter { isExportableSetting(it.key) }),
            MEDICATIONS to enc(ListSerializer(MedicationEntity.serializer()), s.medications),
            REGIMENS to enc(ListSerializer(RegimenEntity.serializer()), s.regimens),
            DOSES to enc(ListSerializer(DoseEventEntity.serializer()), s.doses),
            SKIP_RULES to enc(ListSerializer(SkipRuleEntity.serializer()), s.skipRules),
            AUDIT to enc(ListSerializer(AuditLogEntity.serializer()), s.audit),
            CSV to historyCsv(s, zone).toByteArray(Charsets.UTF_8),
        )
        val manifest = BackupManifest(
            FORMAT, FORMAT_VERSION, appVersion, schemaVersion, exportedAt,
            counts = mapOf("settings" to s.settings.count { isExportableSetting(it.key) }, "medications" to s.medications.size,
                "regimens" to s.regimens.size, "doses" to s.doses.size, "skipRules" to s.skipRules.size, "audit" to s.audit.size),
            sha256 = files.mapValues { sha256(it.value) },
        )
        return linkedMapOf(MANIFEST to json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()) + files
    }

    fun writeZip(files: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun <T> enc(ser: KSerializer<List<T>>, v: List<T>) = json.encodeToString(ser, v).toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------- import

    /** Reads entries with strict limits. Counts bytes actually decompressed, not header sizes. */
    fun readZip(input: InputStream, limits: Limits = Limits()): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    if (e.isDirectory || name !in ALLOWED) throw BackupException("Unexpected file in backup: ${name.take(60)}")
                    if (name in out) throw BackupException("Duplicate file in backup: $name")
                    if (out.size >= ALLOWED.size) throw BackupException("Too many files in backup")
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        entryBytes += n; total += n
                        if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalBytes) throw BackupException("Backup is too large")
                        buf.write(chunk, 0, n)
                    }
                    out[name] = buf.toByteArray()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This is not a valid reMedicord backup file")
        }
        if (out.isEmpty()) throw BackupException("This is not a valid reMedicord backup file")
        return out
    }

    fun readManifest(files: Map<String, ByteArray>): BackupManifest {
        val m = runCatching { strict.decodeFromString(BackupManifest.serializer(), (files[MANIFEST] ?: throw BackupException("Missing manifest")).toString(Charsets.UTF_8)) }
            .getOrElse { if (it is BackupException) throw it else throw BackupException("Backup manifest is damaged") }
        if (m.format != FORMAT) throw BackupException("This is not a reMedicord backup")
        if (m.formatVersion > FORMAT_VERSION) throw BackupException("This backup was made by a newer version of reMedicord")
        return m
    }

    /** Full decode + validation. Throws [BackupException] with a user-readable reason. */
    fun decode(files: Map<String, ByteArray>, currentSchemaVersion: Int, limits: Limits = Limits()): Pair<BackupManifest, BackupSnapshot> {
        val m = readManifest(files)
        if (m.schemaVersion > currentSchemaVersion) throw BackupException("This backup was made by a newer version of reMedicord")
        if (m.schemaVersion < 1) throw BackupException("Unsupported backup version")
        // Older schemas would be migrated forward here (none exist yet: schema 1 is the first).
        DATA_FILES.forEach { name ->
            val bytes = files[name] ?: throw BackupException("Backup is incomplete (missing $name)")
            if (m.sha256[name] != sha256(bytes)) throw BackupException("Backup file is damaged ($name does not match its checksum)")
        }
        val snap = try {
            BackupSnapshot(
                dec(files, SETTINGS, SettingEntity.serializer()), dec(files, MEDICATIONS, MedicationEntity.serializer()),
                dec(files, REGIMENS, RegimenEntity.serializer()), dec(files, DOSES, DoseEventEntity.serializer()),
                dec(files, SKIP_RULES, SkipRuleEntity.serializer()), dec(files, AUDIT, AuditLogEntity.serializer()),
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("Backup contents are invalid")
        }
        validate(snap, m, limits)
        return m to snap
    }

    private fun <T> dec(files: Map<String, ByteArray>, name: String, ser: KSerializer<T>): List<T> =
        strict.decodeFromString(ListSerializer(ser), files.getValue(name).toString(Charsets.UTF_8))

    private fun fail(msg: String): Nothing = throw BackupException("Backup contents are invalid: $msg")

    private fun text(s: String?, max: Int, field: String, newlines: Boolean = false) {
        if (s != null && (s.length > max || !FormValidator.cleanText(s, newlines))) fail(field)
    }

    private val SETTING_KEY = Regex("[A-Za-z0-9._-]{1,100}")
    private val ENTITY_TYPES = setOf("medication", "regimen", "dose_event", "skip_rule")
    private val MAX_AMOUNT = BigDecimal(10_000)

    fun validate(s: BackupSnapshot, m: BackupManifest, limits: Limits = Limits()) {
        val rows = s.settings.size + s.medications.size + s.regimens.size + s.doses.size + s.skipRules.size + s.audit.size
        if (rows > limits.maxRows) fail("too many records")
        val counts = mapOf("settings" to s.settings.size, "medications" to s.medications.size, "regimens" to s.regimens.size,
            "doses" to s.doses.size, "skipRules" to s.skipRules.size, "audit" to s.audit.size)
        if (counts != m.counts) fail("record counts don't match the manifest")

        s.settings.forEach {
            if (!SETTING_KEY.matches(it.key) || !isExportableSetting(it.key)) fail("setting key")
            text(it.value, 1000, "setting value")
        }
        if (s.settings.map { it.key }.toSet().size != s.settings.size) fail("duplicate setting")

        fun <T> uniqueIds(list: List<T>, id: (T) -> Long, what: String): Set<Long> {
            val ids = list.map(id)
            if (ids.any { it <= 0 } || ids.toSet().size != ids.size) fail("$what ids")
            return ids.toSet()
        }
        val medIds = uniqueIds(s.medications, { it.id }, "medication")
        val regIds = uniqueIds(s.regimens, { it.id }, "regimen")
        uniqueIds(s.doses, { it.id }, "dose")
        uniqueIds(s.skipRules, { it.id }, "skip rule")
        uniqueIds(s.audit, { it.id }, "audit")

        s.medications.forEach { md ->
            if (md.name.isBlank()) fail("medication name")
            text(md.name, FormValidator.MAX_NAME, "medication name")
            text(md.instructions, FormValidator.MAX_TEXT, "instructions", newlines = true)
            text(md.bodyLocation, FormValidator.MAX_SHORT, "body location")
            text(md.dressingType, FormValidator.MAX_SHORT, "dressing type")
            text(md.stopNote, FormValidator.MAX_TEXT, "stop note", newlines = true)
            text(md.purposeNote, 200, "purpose")
            md.enabledSites?.let { sites -> if (sites.size > FormValidator.MAX_SITES) fail("sites"); sites.forEach { text(it, FormValidator.MAX_SHORT, "site") } }
            md.homeZone?.let { z -> if (runCatching { ZoneId.of(z) }.isFailure) fail("time zone") }
            md.courseLength?.let { if (it !in 1..1000) fail("course length") }
        }
        s.regimens.forEach { r ->
            if (r.medicationId !in medIds) fail("regimen without medication")
            if (r.doseAmount <= BigDecimal.ZERO || r.doseAmount > MAX_AMOUNT) fail("dose amount")
            if (r.effectiveTo != null && r.effectiveTo < r.effectiveFrom) fail("regimen dates")
            if (runCatching { ScheduleRule.fromJson(r.scheduleRuleJson) }.isFailure) fail("schedule")
            text(r.changeReason, 200, "change reason")
        }
        s.doses.forEach { d ->
            if (d.medicationId !in medIds) fail("dose without medication")
            if (d.regimenId != null && d.regimenId !in regIds) fail("dose regimen")
            d.scheduledZone?.let { z -> if (runCatching { ZoneId.of(z) }.isFailure) fail("dose time zone") }
            d.amount?.let { if (it < BigDecimal.ZERO || it > MAX_AMOUNT) fail("dose amount") }
            text(d.site, FormValidator.MAX_SHORT, "dose site"); text(d.skipReason, 200, "skip reason"); text(d.note, FormValidator.MAX_TEXT, "dose note", newlines = true)
            if (d.snoozeCount !in 0..100 || d.repeatCount !in 0..100) fail("dose counters")
        }
        s.skipRules.forEach { r ->
            if (r.medicationId !in medIds) fail("skip rule without medication")
            r.count?.let { if (it !in 1..365) fail("skip count") }
            text(r.reason, 200, "skip reason"); text(r.note, FormValidator.MAX_TEXT, "skip note", newlines = true)
        }
        s.audit.forEach { a ->
            if (a.entityType !in ENTITY_TYPES) fail("audit type")
            if ((a.beforeJson?.length ?: 0) > 100_000 || (a.afterJson?.length ?: 0) > 100_000) fail("audit size")
            text(a.note, FormValidator.MAX_TEXT, "audit note", newlines = true)
        }
    }

    // ---------------------------------------------------------------- CSV

    /** Human-readable history. Formula injection is neutralised (PLAN.md §7, §12.1). */
    fun historyCsv(s: BackupSnapshot, zone: ZoneId): String {
        val meds = s.medications.associateBy { it.id }
        val regs = s.regimens.associateBy { it.id }
        val dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val sb = StringBuilder("due,medicine,type,status,dose,unit,taken_at,late,as_needed,site,skip_reason,note\r\n")
        s.doses.sortedBy { it.scheduledAt ?: it.takenAt ?: 0 }.forEach { d ->
            val m = meds[d.medicationId]
            val r = d.regimenId?.let { regs[it] }
            fun t(ms: Long?) = ms?.let { dt.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: ""
            sb.append(listOf(t(d.scheduledAt), m?.name ?: "", m?.type?.name ?: "", d.status.name,
                (d.amount ?: r?.doseAmount)?.stripTrailingZeros()?.toPlainString() ?: "", r?.doseUnit?.name ?: "",
                t(d.takenAt), if (d.isLateLog) "yes" else "", if (d.isPrn) "yes" else "", d.site ?: "", d.skipReason ?: "", d.note ?: "")
                .joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Quote every cell; prefix with ' if it could be read as a formula by Excel/Sheets. */
    fun csvCell(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
