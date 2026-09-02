package lv.bolwarra.wetter.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One cached forecast, keyed by rounded coordinates.
 *
 * [fetchedAtEpochSecond] and [providerId] are real columns rather than fields
 * inside the payload, because the app asks about them without needing the
 * forecast: how old is this, and who supplied it. Everything else lives in the
 * JSON — see [StoredForecast] for why.
 */
@Entity(tableName = "forecasts")
internal data class ForecastEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtEpochSecond: Long,
    val providerId: String,
    val payload: String,
)

/**
 * The place the app is showing, as a single row.
 *
 * It has to be on disk rather than in memory because the background refresh runs
 * in its own process lifetime and cannot fetch a location it is unable to read.
 */
@Entity(tableName = "selected_location")
internal data class SelectedLocationEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val region: String?,
    val country: String?,
) {
    companion object {
        /** There is exactly one selected location, so it always occupies row 1. */
        const val SINGLE_ROW = 1
    }
}

@Dao
internal interface ForecastDao {

    @Query("SELECT * FROM forecasts WHERE cacheKey = :cacheKey")
    fun observe(cacheKey: String): Flow<ForecastEntity?>

    @Query("SELECT * FROM forecasts WHERE cacheKey = :cacheKey")
    suspend fun read(cacheKey: String): ForecastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(forecast: ForecastEntity)

    /**
     * Forecasts for places nobody is looking at any more.
     *
     * A cache that only ever grows is a leak with a nicer name. Anything older
     * than the cutoff is past being useful even as stale data.
     */
    @Query("DELETE FROM forecasts WHERE fetchedAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

@Dao
internal interface SelectedLocationDao {

    @Query("SELECT * FROM selected_location WHERE id = 1")
    fun observe(): Flow<SelectedLocationEntity?>

    @Query("SELECT * FROM selected_location WHERE id = 1")
    suspend fun read(): SelectedLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(location: SelectedLocationEntity)
}

@Database(
    entities = [ForecastEntity::class, SelectedLocationEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class WetterDatabase : RoomDatabase() {

    abstract fun forecasts(): ForecastDao

    abstract fun selectedLocation(): SelectedLocationDao

    companion object {
        fun create(context: Context): WetterDatabase = Room.databaseBuilder(
            context.applicationContext,
            WetterDatabase::class.java,
            "wetter.db",
        )
            // Everything in this database is either a cache or a single
            // preference that is trivially re-chosen. Nothing here is
            // irreplaceable, which is the only reason destroying it on a
            // schema change is acceptable — write a Migration the moment
            // that stops being true.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
