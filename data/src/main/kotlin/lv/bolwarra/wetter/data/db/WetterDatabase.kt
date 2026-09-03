package lv.bolwarra.wetter.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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

/**
 * One prediction, kept until the hour it describes has passed and can be marked.
 *
 * This is the first thing in the database that is genuinely irreplaceable. A
 * cached forecast can be fetched again and a selected location re-chosen, but a
 * record of what was predicted a fortnight ago cannot be recovered from anywhere
 * once it is gone - which is why this table arrives with a real migration and why
 * the destructive fallback had to go with it.
 */
@Entity(
    tableName = "forecast_records",
    indices = [
        Index(value = ["cacheKey"]),
        Index(value = ["validAtEpochSecond"]),
    ],
)
internal data class ForecastRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Rounded coordinates, keyed as the forecast cache is. */
    val cacheKey: String,
    val latitude: Double,
    val longitude: Double,
    val validAtEpochSecond: Long,
    val issuedAtEpochSecond: Long,
    val source: String,
    val variable: String,
    val predicted: Double,
    /** Null until the hour has passed and an observation has been found. */
    val observed: Double?,
)

@Dao
internal interface ForecastRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun write(records: List<ForecastRecordEntity>)

    /**
     * Predictions whose hour has passed and which nothing has checked yet.
     *
     * These are what a verification pass works through - the whole backlog of
     * claims the app has made and not yet been held to.
     */
    @Query(
        "SELECT * FROM forecast_records WHERE observed IS NULL " +
            "AND validAtEpochSecond <= :nowEpochSecond " +
            "AND validAtEpochSecond >= :earliestEpochSecond " +
            "ORDER BY validAtEpochSecond",
    )
    suspend fun awaitingVerification(
        nowEpochSecond: Long,
        earliestEpochSecond: Long,
    ): List<ForecastRecordEntity>

    @Query("UPDATE forecast_records SET observed = :observed WHERE id = :id")
    suspend fun markVerified(id: Long, observed: Double)

    /** Verified records for one place, for learning what it gets wrong there. */
    @Query(
        "SELECT * FROM forecast_records WHERE cacheKey = :cacheKey " +
            "AND observed IS NOT NULL AND validAtEpochSecond >= :sinceEpochSecond",
    )
    suspend fun verifiedFor(cacheKey: String, sinceEpochSecond: Long): List<ForecastRecordEntity>

    @Query("SELECT COUNT(*) FROM forecast_records WHERE observed IS NOT NULL")
    suspend fun verifiedCount(): Int

    /**
     * Records too old to be worth keeping.
     *
     * A month, which is long enough for a seasonal pattern to be visible and
     * short enough that a correction learned in February is not still being
     * applied in May.
     */
    @Query("DELETE FROM forecast_records WHERE validAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
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
    entities = [
        ForecastEntity::class,
        SelectedLocationEntity::class,
        ForecastRecordEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class WetterDatabase : RoomDatabase() {

    abstract fun forecasts(): ForecastDao

    abstract fun selectedLocation(): SelectedLocationDao

    abstract fun forecastRecords(): ForecastRecordDao

    companion object {

        /**
         * Adds the verification store.
         *
         * Purely additive - it creates a table and touches nothing that already
         * exists, so an upgrade cannot lose a cached forecast or the selected
         * location.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `forecast_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`validAtEpochSecond` INTEGER NOT NULL, " +
                        "`issuedAtEpochSecond` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`variable` TEXT NOT NULL, " +
                        "`predicted` REAL NOT NULL, " +
                        "`observed` REAL)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_forecast_records_cacheKey` " +
                        "ON `forecast_records` (`cacheKey`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_forecast_records_validAtEpochSecond` " +
                        "ON `forecast_records` (`validAtEpochSecond`)",
                )
            }
        }

        fun create(context: Context): WetterDatabase = Room.databaseBuilder(
            context.applicationContext,
            WetterDatabase::class.java,
            "wetter.db",
        )
            // The destructive fallback that used to be here is deliberately
            // gone. It was justified while everything in this database was
            // either a cache or a preference that is trivially re-chosen -
            // neither of which is a loss worth writing a migration to avoid.
            // The verification store is not like that: it is weeks of
            // accumulated records of what was predicted and what actually
            // happened, it cannot be fetched again from anywhere, and wiping
            // it would silently throw away everything the app had learned
            // about this location. Every schema change from here needs a
            // migration.
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
