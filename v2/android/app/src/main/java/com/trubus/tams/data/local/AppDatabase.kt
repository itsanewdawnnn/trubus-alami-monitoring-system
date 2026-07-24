package com.trubus.tams.data.local

import android.content.Context
import androidx.room.*
import com.trubus.tams.data.model.OfflineLocation

@Dao
interface OfflineLocationDao {
    // Returns the generated row id (Room's default autoGenerate rowid)
    // rather than Unit -- MemberRepository.persistLocationForUpload() needs
    // it to delete this exact write-ahead row later without touching any
    // other queued row. See that function's doc comment.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: OfflineLocation): Long

    @Query("SELECT * FROM tams_offline_locations ORDER BY timestamp ASC")
    suspend fun getAll(): List<OfflineLocation>

    @Query("DELETE FROM tams_offline_locations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    // Called on logout: any location still queued at that point can never be
    // safely sent later, since a future sync would run under whichever
    // account logs in next -- see MemberRepository.logout()/syncOfflineLocations().
    @Query("DELETE FROM tams_offline_locations WHERE userId = :userId")
    suspend fun deleteByUserId(userId: Int)

    // Safety net for extended offline periods: keeps only the most recent
    // [keepCount] queued points so the local queue can't grow unbounded.
    @Query(
        """
        DELETE FROM tams_offline_locations
        WHERE id NOT IN (
            SELECT id FROM tams_offline_locations ORDER BY timestamp DESC LIMIT :keepCount
        )
        """,
    )
    suspend fun trimToMostRecent(keepCount: Int)
}

@Database(
    entities = [
        OfflineLocation::class
    ],
    // v5: added UNIQUE constraint on (userId, recordedAt) to OfflineLocation.
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offlineLocationDao(): OfflineLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "member_monitor_local_db"
                )
                // Any unhandled schema bump wipes and recreates every table --
                // fine here since this is only a transient offline retry queue.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
