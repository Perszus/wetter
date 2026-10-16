// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * One cached forecast, keyed by rounded coordinates. Age and provider are columns
 * so they can be read without decoding the JSON payload ([StoredForecast]).
 */
@Entity(tableName = "forecasts")
internal data class ForecastEntity(
    @PrimaryKey val cacheKey: String,
    val fetchedAtEpochSecond: Long,
    val providerId: String,
    val payload: String,
)

/**
 * The place the app is showing, as a single row on disk, so the background work
 * can read it without the screen.
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
    /** Defaulted, so a row written before the column existed still reads back. */
    val elevationMetres: Double? = null,
) {
    companion object {
        /** There is one selected location, always row 1. */
        const val SINGLE_ROW = 1
    }
}

/**
 * One prediction, kept until its hour has passed and it can be scored. This is
 * the one table whose data cannot be fetched again, which is why every schema
 * change from here has a real migration.
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
     * How much the projection believed itself and how well its sweeps matched, both
     * 0..1 and null for anything that is not a radar projection, so errors can be
     * told apart by cause.
     */
    val confidence: Double? = null,
    val motionQuality: Double? = null,
)

/**
 * What is known about a provider's recent behaviour, kept across restarts so a
 * service that has been down is not retried from scratch (docs/providers.md).
 * Never aggregated or reported anywhere.
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
    /** Newest first in the list. */
    val addedAtEpochSecond: Long,
)

/**
 * The last radar projection for a place, so opening the app does not start from
 * nothing. Only the samples under the place are kept, with absolute times, so the
 * part of a stale row still in the future stays usable.
 */
@Entity(tableName = "radar_series")
internal data class RadarSeriesEntity(
    @PrimaryKey val cacheKey: String,
    /** The sweep this was projected from, for deciding whether it has been overtaken. */
    val sweepAtEpochSecond: Long,
    val payload: String,
)

/** The last ensemble for a place, so a cold start has models to take a median of. */
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

    /** Runs publish hourly; a day-old spread is not worth drawing. */
    @Query("DELETE FROM model_ensembles WHERE fetchedAtEpochSecond < :cutoffEpochSecond")
    suspend fun deleteOlderThan(cutoffEpochSecond: Long)
}

@Dao
internal interface RadarSeriesDao {

    @Query("SELECT * FROM radar_series WHERE cacheKey = :cacheKey")
    suspend fun read(cacheKey: String): RadarSeriesEntity?

    /**
     * The same row, watched: Room re-emits on every write, so a sweep collected in
     * the background reaches an open screen.
     */
    @Query("SELECT sweepAtEpochSecond FROM radar_series WHERE cacheKey = :cacheKey")
    fun observeSweep(cacheKey: String): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(series: RadarSeriesEntity)

    /** A projection is spent within hours. */
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

    /** Predictions whose hour has passed and which nothing has scored yet. */
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
     * When the oldest unsettled claim from one source was due, so scoring knows how
     * far back to fetch sweeps.
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
     * Records too old to keep: a month, long enough for a seasonal pattern and short
     * enough that an old correction is not applied in a new season.
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

    /** Forecasts for places nobody is looking at any more. */
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
 * The computed normals for a place, as one row; the archive they were built from
 * is not kept.
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
 * What the reader has chosen, as a single row the background work can read.
 * Every column is defaulted, so a new setting needs no data migration.
 */
@Entity(tableName = "preferences")
internal data class PreferencesEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val temperatureUnit: String = "",
    val windUnit: String = "",
    val precipitationUnit: String = "",
    val theme: String = "",
    /** Whether severe weather is worth a notification. On by default. */
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
 * A warning that has already been given, kept only long enough not to repeat it.
 * Keyed by place and by the domain's key (kind, severity and day,
 * `HazardAnnouncements.keyFor`).
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

        /** Adds the verification store. Additive. */
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

        /** Adds saved places. Additive. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                // The selected place gains an elevation too.
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

        /** Adds the kept radar projection. Additive. */
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
         * Two columns describing how much a projection deserved belief. Added rather than
         * rebuilding the table, so existing records survive with null.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `forecast_records` ADD COLUMN `confidence` REAL")
                connection.execSQL("ALTER TABLE `forecast_records` ADD COLUMN `motionQuality` REAL")
            }
        }

        /** Adds the kept ensemble. */
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

        /** Adds the climate normals store. Additive. */
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
         * Adds the preferences row, every column defaulted so an absent row reads as the
         * defaults.
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
         * Adds the record of warnings already given, and the warnings switch, defaulting
         * to on.
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
            // No destructive fallback: the verification records cannot be fetched again, so
            // every schema change needs a migration.
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
