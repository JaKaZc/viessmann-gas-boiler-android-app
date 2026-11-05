package com.vigasboiler.app.history

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.SnapshotListener

/** What [HistoryBackup] needs from the history database. */
interface HistoryStore {
    suspend fun rows(fromMillis: Long, untilMillis: Long): List<HistoryRow>
    suspend fun oldestMillis(): Long?
    suspend fun insert(values: List<TimedValue>)
}

/**
 * One day of history as gzipped text: the day's series, then one line per snapshot with
 * `series index=value` pairs. A year fits in a few MB; the database needs about 100.
 */
object HistoryBackupFormat {
    private const val VERSION = "1"

    fun encode(rows: List<HistoryRow>): ByteArray {
        val series = rows.map { Triple(it.feature, it.property, it.unit) }.distinct()
        val index = series.withIndex().associate { (i, s) -> s to i }
        val text = buildString {
            appendLine(VERSION)
            series.forEach { (feature, property, unit) -> appendLine("$feature\t$property\t${unit.orEmpty()}") }
            appendLine()
            rows.groupBy { it.timestampMillis }.forEach { (t, values) ->
                append(t)
                values.forEach { r -> append('\t').append(index.getValue(Triple(r.feature, r.property, r.unit))).append('=').append(number(r.value)) }
                appendLine()
            }
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.encodeToByteArray()) }
        return out.toByteArray()
    }

    /** Throws on a file that is damaged or from a newer version. */
    fun decode(bytes: ByteArray): List<TimedValue> {
        val lines = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString().lines().iterator()
        require(lines.next() == VERSION)
        val series = buildList {
            while (true) {
                val line = lines.next()
                if (line.isEmpty()) break
                val (feature, property, unit) = line.split('\t')
                add(Triple(feature, property, unit.ifEmpty { null }))
            }
        }
        return buildList {
            lines.forEach { line ->
                if (line.isEmpty()) return@forEach
                val parts = line.split('\t')
                val t = parts[0].toLong()
                parts.drop(1).forEach { pair ->
                    val (i, value) = pair.split('=')
                    val (feature, property, unit) = series[i.toInt()]
                    add(TimedValue(t, HistoryValue(feature, property, value.toDouble(), unit)))
                }
            }
        }
    }

    private fun number(value: Double) = value.toString().removeSuffix(".0")
}

/**
 * Keeps [dir] up to date with one [HistoryBackupFormat] file per day. Android's Auto Backup saves
 * that folder (see res/xml/data_extraction_rules.xml), not the database, which would pass its
 * 25 MB limit after about three months. [restore] reads the files back on the first start after
 * a reinstall or on a new phone. [restoredMarker] lies in the no-backup folder, so it is missing
 * exactly then. Until the restore has finished nothing is written, so a fresh, nearly empty
 * database can never overwrite the saved days.
 *
 * Demo data never gets in here, which also lets the real history return when a demo ends.
 */
class HistoryBackup(
    private val dir: File,
    private val restoredMarker: File,
    private val store: HistoryStore,
    private val retentionDays: suspend () -> Int,
    private val isDemo: suspend () -> Boolean,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : SnapshotListener {
    private val mutex = Mutex()
    private var lastSyncMillis = 0L

    override suspend fun onSnapshot(previous: FeatureSnapshot?, current: FeatureSnapshot) {
        // The app can refresh every minute; the system backs up about once a night.
        if (clock() - lastSyncMillis >= MIN_INTERVAL_MILLIS) sync()
    }

    /** Writes missing days, today and yesterday (yesterday may have ended after the last write). */
    suspend fun sync() = mutex.withLock {
        if (!restoredMarker.exists() || isDemo()) return@withLock
        lastSyncMillis = clock()
        val zone = zone()
        val today = date(clock(), zone)
        val keepFrom = today.minusDays(retentionDays().toLong())
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            files().filter { dayOf(it)?.isBefore(keepFrom) != false }.forEach { it.delete() }
        }
        val oldest = store.oldestMillis()?.let { date(it, zone) } ?: return@withLock
        var day = maxOf(oldest, keepFrom)
        while (!day.isAfter(today)) {
            val file = File(dir, "$day$SUFFIX")
            if (!file.exists() || !day.isBefore(today.minusDays(1))) {
                val rows = store.rows(startOf(day, zone), startOf(day.plusDays(1), zone))
                if (rows.isNotEmpty()) withContext(Dispatchers.IO) { write(file, HistoryBackupFormat.encode(rows)) }
            }
            day = day.plusDays(1)
        }
    }

    /** Reads the saved days into the database once; an interrupted restore simply runs again. */
    suspend fun restore() = mutex.withLock {
        if (restoredMarker.exists() || isDemo()) return@withLock
        val keepFrom = date(clock(), zone()).minusDays(retentionDays().toLong())
        withContext(Dispatchers.IO) { files().sortedBy { it.name } }.forEach { file ->
            if (dayOf(file)?.isBefore(keepFrom) != false) return@forEach
            // A damaged day is skipped: the others are still worth having.
            val values = withContext(Dispatchers.IO) { runCatching { HistoryBackupFormat.decode(file.readBytes()) }.getOrNull() }
            if (!values.isNullOrEmpty()) store.insert(values)
        }
        withContext(Dispatchers.IO) {
            restoredMarker.parentFile?.mkdirs()
            restoredMarker.createNewFile()
        }
        Unit
    }

    /** The database is about to be replaced: nothing is written until the next [restore]. */
    suspend fun suspendUntilRestore() = mutex.withLock {
        withContext(Dispatchers.IO) { restoredMarker.delete() }
        Unit
    }

    private fun files() = dir.listFiles().orEmpty().filter { it.name.endsWith(SUFFIX) }

    private fun dayOf(file: File) = runCatching { LocalDate.parse(file.name.removeSuffix(SUFFIX)) }.getOrNull()

    /** Through a temporary file, so a backup never sees a half-written day. */
    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(dir, file.name + ".tmp")
        temporary.writeBytes(bytes)
        temporary.renameTo(file)
    }

    private fun date(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun startOf(day: LocalDate, zone: ZoneId) = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val SUFFIX = ".gz"
        const val MIN_INTERVAL_MILLIS = 30 * 60_000L
    }
}
