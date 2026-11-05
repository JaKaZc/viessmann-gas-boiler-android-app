package com.vigasboiler.app.history

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.vigasboiler.app.data.HistoryRow

class HistoryBackupTest {

    @get:Rule val folder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val today = LocalDate.of(2026, 11, 9)
    private var now = at(today, 12)
    private var demo = false
    private val dir get() = File(folder.root, "history-backup")
    private val marker get() = File(folder.root, "no-backup/restored")

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun row(t: Long, value: Double, property: String = "value", unit: String? = "celsius") = HistoryRow(t, "heating.sensors.temperature.outside", property, value, unit)

    private class FakeStore(rows: List<HistoryRow> = emptyList()) : HistoryStore {
        val rows = rows.toMutableList()
        var inserts = 0
        override suspend fun rows(fromMillis: Long, untilMillis: Long) = rows.filter { it.timestampMillis in fromMillis until untilMillis }
        override suspend fun oldestMillis() = rows.minOfOrNull { it.timestampMillis }
        override suspend fun insert(values: List<TimedValue>) {
            inserts++
            rows += values.map { (t, v) -> HistoryRow(t, v.feature, v.property, v.value, v.unit) }
        }
    }

    private fun backup(store: HistoryStore, retention: Int = 365) =
        HistoryBackup(dir, marker, store, { retention }, { demo }, { zone }, { now })

    private fun names() = dir.list().orEmpty().sorted()

    @Test
    fun `a day survives the round trip through the file format`() {
        val rows = listOf(row(1000, 20.7), row(1000, 1.0, "active", null), row(2000, 1234567.25), row(2000, -3.5, "active", null))
        val decoded = HistoryBackupFormat.decode(HistoryBackupFormat.encode(rows))
        assertEquals(rows, decoded.map { (t, v) -> HistoryRow(t, v.feature, v.property, v.value, v.unit) })
    }

    @Test
    fun `a year of snapshots stays far below the backup limit`() {
        val day = (0 until 48).flatMap { i -> (0 until 94).map { s -> HistoryRow(i * 1_800_000L, "heating.feature.number$s", "value", 20.0 + (i * s) % 17 / 10.0, "celsius") } }
        assertTrue(HistoryBackupFormat.encode(day).size * 365 < 5_000_000)
    }

    @Test
    fun `reinstall - the saved days come back into an empty database`() = runTest {
        val old = FakeStore(listOf(row(at(today.minusDays(5), 8), 4.0), row(at(today.minusDays(5), 9), 5.0), row(at(today, 8), 6.0)))
        backup(old).restore() // first start of this version
        backup(old).sync()
        assertEquals(listOf("2026-11-04.gz", "2026-11-09.gz"), names())

        marker.delete() // what a reinstall leaves: the backed-up folder, no marker, no database
        val fresh = FakeStore()
        backup(fresh).restore()
        assertEquals(old.rows.toSet(), fresh.rows.toSet())
        assertTrue(marker.exists())

        backup(fresh).restore()
        assertEquals("runs only once", 2, fresh.inserts)
    }

    @Test
    fun `nothing is written before the restore, so saved days cannot be overwritten`() = runTest {
        val old = FakeStore(listOf(row(at(today, 7), 4.0), row(at(today, 8), 5.0)))
        backup(old).restore()
        backup(old).sync()
        marker.delete()

        val fresh = FakeStore(listOf(row(at(today, 11), 9.0))) // a refresh got in first
        backup(fresh).sync()
        assertEquals(2, HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).size)
        backup(fresh).restore()
        backup(fresh).sync()
        assertEquals(3, HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).size)
    }

    @Test
    fun `an interrupted restore runs again`() = runTest {
        val old = FakeStore(listOf(row(at(today, 7), 4.0)))
        backup(old).restore()
        backup(old).sync()
        marker.delete()
        val failing = object : HistoryStore by FakeStore() {
            override suspend fun insert(values: List<TimedValue>) = throw IllegalStateException("killed")
        }
        runCatching { backup(failing).restore() }
        assertFalse(marker.exists())
        val fresh = FakeStore()
        backup(fresh).restore()
        assertEquals(1, fresh.rows.size)
    }

    @Test
    fun `a damaged file is skipped, the other days are restored`() = runTest {
        val old = FakeStore(listOf(row(at(today.minusDays(1), 7), 4.0), row(at(today, 7), 5.0)))
        backup(old).restore()
        backup(old).sync()
        File(dir, "2026-11-08.gz").writeText("not a backup")
        marker.delete()
        val fresh = FakeStore()
        backup(fresh).restore()
        assertEquals(listOf(5.0), fresh.rows.map { it.value })
    }

    @Test
    fun `demo data is never saved and the real history returns after the demo`() = runTest {
        val store = FakeStore(listOf(row(at(today, 7), 4.0)))
        val backup = backup(store)
        backup.restore()
        backup.sync()

        demo = true
        store.rows.clear()
        store.rows += row(at(today, 8), 99.0)
        backup.sync()
        assertEquals(listOf(4.0), HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).map { it.value.value })

        // Leaving the demo: writing stops, the demo data goes, then the saved days come back.
        backup.suspendUntilRestore()
        demo = false
        store.rows.clear()
        backup.restore()
        assertEquals(listOf(4.0), store.rows.map { it.value })
    }

    @Test
    fun `days past the retention are dropped and older days are not rewritten`() = runTest {
        val store = FakeStore(listOf(row(at(today.minusDays(40), 7), 1.0), row(at(today.minusDays(3), 7), 2.0), row(at(today, 7), 3.0)))
        val backup = backup(store, retention = 30)
        backup.restore()
        backup.sync()
        assertEquals(listOf("2026-11-06.gz", "2026-11-09.gz"), names())

        val written = File(dir, "2026-11-06.gz").readBytes()
        store.rows += row(at(today.minusDays(3), 8), 7.0)
        backup.sync()
        assertTrue(written.contentEquals(File(dir, "2026-11-06.gz").readBytes()))
    }

    @Test
    fun `snapshots are saved at most every 30 minutes`() = runTest {
        val store = FakeStore(listOf(row(at(today, 7), 1.0)))
        val backup = backup(store)
        backup.restore()
        val snapshot = com.vigasboiler.app.feature.FeatureSnapshot(emptyList(), emptyList(), now)
        backup.onSnapshot(null, snapshot)
        assertEquals(1, HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).size)
        store.rows += row(at(today, 8), 2.0)
        now += 10 * 60_000L
        backup.onSnapshot(null, snapshot)
        assertEquals(1, HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).size)
        now += 21 * 60_000L
        backup.onSnapshot(null, snapshot)
        assertEquals(2, HistoryBackupFormat.decode(File(dir, "2026-11-09.gz").readBytes()).size)
    }
}
