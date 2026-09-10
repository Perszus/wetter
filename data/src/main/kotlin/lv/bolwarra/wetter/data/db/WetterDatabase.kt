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
    /**
     * Defaulted, so a row written before the column existed still reads back.
     *
     * Dropped on the way through, the observation layer would silently stop
     * correcting for height at the one place it matters most - the one being
     * looked at.
     */
    val elevationMetres: Double? = null,
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
    /**
     * How much the projection believed itself, 0..1, and how well the two
     * sweeps it was built from actually matched.
     *
     * Both null for anything that is not a radar projection. They are here
     * because an error is only worth learning from if something distinguishes
     * the times it happens from the times it does not: sixteen hours of records
     * showed the nowcast over-forecasting more the further ahead it looked, and
     * with these it becomes possible to ask whether that is the extrapolation
     * decaying or simply a poor motion estimate being carried forward.
     */
    val confidence: Double? = null,
    val motionQuality: Double? = null,
)

/**
 * A place somebody chose to keep.
 *
 * Keyed by rounded coordinates rather than by the service's own id, so the same
 * place found twice through different spellings is stored once, and so the table
 * does not depend on a gazetteer's identifiers staying stable.
 */
/**
 * What is known about a provider's recent behaviour.
 *
 * Kept across a restart because the knowledge is slow to earn and cheap to
 * store. Held only in memory, an app that woke to a service which had been down
 * for hours would try it, fail, and learn that fact again from scratch every
 * time - which is exactly the request the backoff exists to avoid making.
 *
 * Nothing here is aggregated or reported anywhere. It is a note to self about
 * who to ask first (docs/providers.md).
 */
@Entity(tableName = "provider_health")
internal data class ProviderHealthEntity(
    @PrimaryKey val providerId: String,
    val lastSuccessEpochSecond: Long?,
    val lastFailureEpochSecond: Long?,
    val consecutiveFailures: Int,
    val cooldownUntilEpochSecond: Long?,
)

@Dao
internal interface ProviderHealthDao {

    @Query("SELECT * FROM provider_health")
    suspend fun all(): List<ProviderHealthEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(rows: List<ProviderHealthEntity>)
}

@Entity(tableName = "saved_locations")
internal data class SavedLocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val region: String?,
    val country: String?,
    val elevationMetres: Double?,
    /** Newest first in the list, so a place just added is where it is expected. */
    val addedAtEpochSecond: Long,
)

/**
 * The last radar projection for a place, so opening the app does not start from
 * nothing.
 *
 * Only the sampled series is kept, not the field it came from. The projection
 * covers a 768 by 768 grid of rates, which is megabytes and none of which the
 * screen ever reads: the chart consumes the dozen values under the user's own
 * coordinates. Storing those is a few hundred bytes and loses nothing.
 *
 * The samples carry absolute times rather than lead offsets, which is what makes
 * a stale row still worth having - the parts of it that are still in the future
 * remain true, and the rest simply falls outside the window.
 */
@Entity(tableName = "radar_series")
internal data class RadarSeriesEntity(
    @PrimaryKey val cacheKey: String,
    /** The sweep this was projected from, for deciding whether it has been overtaken. */
    val sweepAtEpochSecond: Long,
    val payload: String,
)

/**
 * The last ensemble for a place, kept so a cold start has models to average.
 *
 * The forecast and the radar projection were both already on disk; this was
 * not, and it was the only one of the three that mattered for everything past
 * the first hour. So every fresh process drew that stretch from the single
 * chosen provider until a fetch came back - which is exactly when a wet evening
 * several models agreed on could render as flat and dry.
 */
@Entity(tableName = "model_ensembles")
internal data class EnsembleEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtEpochSecond: Long,
    val payload: String,
)

@Dao
internal interface EnsembleDao {

    @Query("SELECT * FROM model_ensembles WHERE cacheKey = :cacheKey")
    suspend fun read(cacheKey: String): EnsembleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(ensemble: EnsembleEntity)

    /** Runs publish hourly; a day-old spread describes nothing worth drawing. */
    @Query("DELETE FROM model_ensembles WHERE fetchedAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

@Dao
internal interface RadarSeriesDao {

    @Query("SELECT * FROM radar_series WHERE cacheKey = :cacheKey")
    suspend fun read(cacheKey: String): RadarSeriesEntity?

    /**
     * The same row, watched. Room re-emits on every write to the table, which is
     * how a sweep collected by the background worker reaches a screen that is
     * already open without the screen having to go looking for it.
     */
    @Query("SELECT sweepAtEpochSecond FROM radar_series WHERE cacheKey = :cacheKey")
    fun observeSweep(cacheKey: String): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(series: RadarSeriesEntity)

    /** Nothing here is worth keeping for long; a projection is spent within hours. */
    @Query("DELETE FROM radar_series WHERE sweepAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

@Dao
internal interface SavedLocationDao {

    @Query("SELECT * FROM saved_locations ORDER BY addedAtEpochSecond DESC")
    fun observe(): Flow<List<SavedLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(location: SavedLocationEntity)

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun delete(id: String)
}

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

    /**
     * When the oldest unsettled claim from one source was due, if any.
     *
     * Used to decide how far back to fetch sweeps. A claim can only be marked
     * while a frame near its moment is in hand, so after the device has slept
     * the backlog needs sweeps older than a routine run would bother with.
     */
    @Query(
        "SELECT MIN(validAtEpochSecond) FROM forecast_records " +
            "WHERE observed IS NULL AND source = :source " +
            "AND validAtEpochSecond <= :nowEpochSecond " +
            "AND validAtEpochSecond >= :earliestEpochSecond",
    )
    suspend fun oldestAwaiting(
        source: String,
        nowEpochSecond: Long,
        earliestEpochSecond: Long,
    ): Long?

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

/**
 * The computed normals for a place, as one row.
 *
 * The eighty kilobytes of archive they were built from are not kept. A decade of
 * daily rows is raw material, the three hundred and sixty-six normals are the
 * answer, and the answer does not change until the location does - so what is
 * stored is the answer.
 */
@Entity(tableName = "climate_normals")
internal data class ClimateNormalsEntity(
    @PrimaryKey val cacheKey: String,
    val builtAtEpochSecond: Long,
    val payload: String,
)

@Dao
internal interface ClimateNormalsDao {

    @Query("SELECT * FROM climate_normals WHERE cacheKey = :cacheKey")
    suspend fun read(cacheKey: String): ClimateNormalsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(normals: ClimateNormalsEntity)

    @Query("DELETE FROM climate_normals WHERE builtAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

/**
 * What the reader has chosen, as a single row.
 *
 * Stored the way [SelectedLocationEntity] is, and for the same reason: the
 * background worker redraws the widget in its own process lifetime and has to be
 * able to read the units without a screen ever having been open.
 *
 * Every column is defaulted, so a row written before a preference existed still
 * reads back - which is what lets a new setting ship without a migration that
 * touches data.
 */
@Entity(tableName = "preferences")
internal data class PreferencesEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val temperatureUnit: String = "",
    val windUnit: String = "",
    val precipitationUnit: String = "",
    val theme: String = "",
    /**
     * Whether severe weather is worth a notification. On by default: somebody
     * who installed a weather app wants to be told a storm is coming, and a
     * warning nobody opted into is the one kind of interruption that earns its
     * keep.
     */
    val warnings: Boolean = true,
) {
    companion object {
        const val SINGLE_ROW = 1
    }
}

@Dao
internal interface PreferencesDao {

    @Query("SELECT * FROM preferences WHERE id = 1")
    fun observe(): Flow<PreferencesEntity?>

    @Query("SELECT * FROM preferences WHERE id = 1")
    suspend fun read(): PreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(preferences: PreferencesEntity)
}

/**
 * A warning that has already been given.
 *
 * One row per thing said, kept only long enough to stop it being said again.
 * The key comes from the domain (`HazardAnnouncements.keyFor`) and carries the
 * kind, the severity and the day - so this table never needs to know what a
 * hazard is, only that this one has been mentioned.
 *
 * Keyed by place as well, because the same night is a frost in one town and not
 * in another, and somebody who keeps two places wants to hear about both.
 */
@Entity(tableName = "announced_hazards", primaryKeys = ["cacheKey", "hazardKey"])
internal data class AnnouncedHazardEntity(
    val cacheKey: String,
    val hazardKey: String,
    val announcedAtEpochSecond: Long,
)

@Dao
internal interface AnnouncedHazardDao {

    @Query("SELECT hazardKey FROM announced_hazards WHERE cacheKey = :cacheKey")
    suspend fun keysFor(cacheKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(announced: List<AnnouncedHazardEntity>)

    @Query("DELETE FROM announced_hazards WHERE announcedAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

@Database(
    entities = [
        ForecastEntity::class,
        SelectedLocationEntity::class,
        ForecastRecordEntity::class,
        SavedLocationEntity::class,
        RadarSeriesEntity::class,
        EnsembleEntity::class,
        ProviderHealthEntity::class,
        ClimateNormalsEntity::class,
        PreferencesEntity::class,
        AnnouncedHazardEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
internal abstract class WetterDatabase : RoomDatabase() {

    abstract fun providerHealth(): ProviderHealthDao

    abstract fun forecasts(): ForecastDao

    abstract fun selectedLocation(): SelectedLocationDao

    abstract fun forecastRecords(): ForecastRecordDao

    abstract fun savedLocations(): SavedLocationDao

    abstract fun radarSeries(): RadarSeriesDao

    abstract fun ensembles(): EnsembleDao

    abstract fun climateNormals(): ClimateNormalsDao

    abstract fun preferences(): PreferencesDao

    abstract fun announcedHazards(): AnnouncedHazardDao

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

        /**
         * Adds the places somebody has kept.
         *
         * Additive like the one before it: a new table, nothing existing
         * touched, so an upgrade cannot lose a saved place, the selected
         * location or the verification history.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                // The selected place gains a height too, or picking a searched
                // one would quietly discard the elevation it arrived with.
                connection.execSQL(
                    "ALTER TABLE `selected_location` ADD COLUMN `elevationMetres` REAL",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_locations` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`zoneId` TEXT NOT NULL, " +
                        "`region` TEXT, " +
                        "`country` TEXT, " +
                        "`elevationMetres` REAL, " +
                        "`addedAtEpochSecond` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /** Adds the kept radar projection. Additive, like the two before it. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `radar_series` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`sweepAtEpochSecond` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `provider_health` (" +
                        "`providerId` TEXT NOT NULL, " +
                        "`lastSuccessEpochSecond` INTEGER, " +
                        "`lastFailureEpochSecond` INTEGER, " +
                        "`consecutiveFailures` INTEGER NOT NULL, " +
                        "`cooldownUntilEpochSecond` INTEGER, " +
                        "PRIMARY KEY(`providerId`))",
                )
            }
        }

        /**
         * Two columns describing how much a projection deserved to be believed.
         *
         * Added rather than the table rebuilt, so the weeks of records already
         * in there survive - they are the whole reason this database stopped
         * having a destructive fallback. Existing rows get null, which reads
         * correctly as "nobody wrote this down at the time".
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `forecast_records` ADD COLUMN `confidence` REAL")
                connection.execSQL("ALTER TABLE `forecast_records` ADD COLUMN `motionQuality` REAL")
            }
        }

        /** Somewhere to keep the ensemble, so a cold start never waits for one. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `model_ensembles` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`fetchedAtEpochSecond` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))",
                )
            }
        }

        /**
         * Adds the climate normals store.
         *
         * Purely additive, like every migration before it: a new table and
         * nothing touched that already exists.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `climate_normals` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`builtAtEpochSecond` INTEGER NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`))",
                )
            }
        }

        /**
         * Adds the preferences row.
         *
         * Additive, and every column defaulted so the absent row reads as the
         * defaults rather than as a failure - the app has to work on the launch
         * before anybody has opened Settings.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `preferences` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`temperatureUnit` TEXT NOT NULL DEFAULT '', " +
                        "`windUnit` TEXT NOT NULL DEFAULT '', " +
                        "`precipitationUnit` TEXT NOT NULL DEFAULT '', " +
                        "`theme` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        /**
         * Adds the record of warnings already given, and the switch for them.
         *
         * Additive on both counts. The new column carries a default of 1, so an
         * upgrade arrives with warnings on rather than with a preference nobody
         * chose reading as off.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `announced_hazards` (" +
                        "`cacheKey` TEXT NOT NULL, " +
                        "`hazardKey` TEXT NOT NULL, " +
                        "`announcedAtEpochSecond` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cacheKey`, `hazardKey`))",
                )
                connection.execSQL(
                    "ALTER TABLE `preferences` " +
                        "ADD COLUMN `warnings` INTEGER NOT NULL DEFAULT 1",
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
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
            .build()
    }
}
