package si.vicare.customapp.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import si.vicare.customapp.ratelimit.ApiCallLog

@Entity(tableName = "api_call", indices = [Index("timestampMillis")])
data class ApiCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
)

/** One (feature, property) pair, e.g. `heating.sensors.temperature.outside` / `value` in celsius. */
@Entity(tableName = "series", indices = [Index(value = ["feature", "property"], unique = true)])
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feature: String,
    val property: String,
    val unit: String?,
)

/**
 * Long table of numeric feature properties, one row per value per snapshot. Feature, property
 * and unit are normalised into [SeriesEntity] to keep a year of 30-minute snapshots small.
 */
@Entity(
    tableName = "snapshot_value",
    primaryKeys = ["seriesId", "timestampMillis"],
    indices = [Index("timestampMillis")],
)
data class SnapshotValueEntity(
    val seriesId: Long,
    val timestampMillis: Long,
    val value: Double,
)

/** A value joined with its series, as exported to CSV. */
data class HistoryRow(
    val timestampMillis: Long,
    val feature: String,
    val property: String,
    val value: Double,
    val unit: String?,
)

@Dao
interface ApiCallDao {
    @Insert
    suspend fun insert(call: ApiCallEntity)

    @Query("SELECT COUNT(*) FROM api_call WHERE timestampMillis >= :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM api_call WHERE timestampMillis < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM series")
    suspend fun allSeries(): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeries(series: SeriesEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertValues(values: List<SnapshotValueEntity>)

    @Query(
        "SELECT v.timestampMillis AS timestampMillis, s.feature AS feature, s.property AS property, v.value AS value, s.unit AS unit " +
            "FROM snapshot_value v JOIN series s ON s.id = v.seriesId " +
            "WHERE s.feature = :feature AND s.property = :property AND v.timestampMillis >= :since ORDER BY v.timestampMillis",
    )
    fun series(feature: String, property: String, since: Long): Flow<List<HistoryRow>>

    @Query(
        "SELECT v.timestampMillis AS timestampMillis, s.feature AS feature, s.property AS property, v.value AS value, s.unit AS unit " +
            "FROM snapshot_value v JOIN series s ON s.id = v.seriesId " +
            "WHERE v.timestampMillis >= :since ORDER BY v.timestampMillis, s.feature, s.property",
    )
    suspend fun allSince(since: Long): List<HistoryRow>

    @Query("DELETE FROM snapshot_value WHERE timestampMillis < :before")
    suspend fun deleteBefore(before: Long)
}

@Database(entities = [ApiCallEntity::class, SeriesEntity::class, SnapshotValueEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apiCallDao(): ApiCallDao
    abstract fun snapshotDao(): SnapshotDao
}

class RoomApiCallLog(private val dao: ApiCallDao) : ApiCallLog {
    override suspend fun record(timestampMillis: Long) = dao.insert(ApiCallEntity(timestampMillis = timestampMillis))
    override suspend fun countSince(sinceMillis: Long): Int = dao.countSince(sinceMillis)
    override suspend fun pruneBefore(beforeMillis: Long) = dao.deleteBefore(beforeMillis)
}
