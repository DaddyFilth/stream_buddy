package com.streambuddy.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.streambuddy.app.stream.CacheState
import com.streambuddy.app.stream.StreamKind
import com.streambuddy.app.util.Format
import kotlinx.coroutines.flow.Flow

/**
 * One entry of the pre-play queue.
 *
 * [state] and [kind] are stored as enum names (Room has no built-in enum support) and exposed
 * through the typed accessors below.
 */
@Entity(tableName = "streams")
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val url: String,
    val kind: String = StreamKind.UNKNOWN.name,
    val state: String = CacheState.QUEUED.name,
    val cachedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val cachedMillis: Long = 0L,
    val totalMillis: Long = 0L,
    val prefetchMillis: Long = 0L,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val streamKind: StreamKind get() = StreamKind.fromName(kind)
    val cacheState: CacheState get() = CacheState.fromName(state)

    /** Percentage of the requested pre-play window (or whole file) that is cached. */
    val progressPercent: Int?
        get() = when {
            totalMillis > 0L -> Format.percent(cachedMillis, totalMillis)
            totalBytes > 0L -> Format.percent(cachedBytes, totalBytes)
            else -> null
        }

    /** `2:00 of 12:34 cached`, `48.2 MB cached`, ... */
    val progressDetail: String
        get() = when {
            totalMillis > 0L -> "${Format.duration(cachedMillis)} of ${Format.duration(totalMillis)} cached"
            cachedBytes > 0L -> "${Format.bytes(cachedBytes)} cached"
            else -> "Nothing cached yet"
        }
}

@Dao
interface StreamDao {

    @Query("SELECT * FROM streams ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<StreamEntity>>

    /** One-shot read for the predictor, which ranks on a worker thread. */
    @Query("SELECT * FROM streams ORDER BY addedAt DESC")
    suspend fun allOnce(): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): StreamEntity?

    @Insert
    suspend fun insert(stream: StreamEntity): Long

    @Query("UPDATE streams SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: String, error: String?, now: Long)

    @Query(
        """
        UPDATE streams SET
            state = :state,
            cachedBytes = :cachedBytes,
            totalBytes = :totalBytes,
            cachedMillis = :cachedMillis,
            totalMillis = :totalMillis,
            prefetchMillis = :prefetchMillis,
            error = :error,
            updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: Long,
        state: String,
        cachedBytes: Long,
        totalBytes: Long,
        cachedMillis: Long,
        totalMillis: Long,
        prefetchMillis: Long,
        error: String?,
        now: Long,
    )

    @Query("UPDATE streams SET prefetchMillis = :prefetchMillis, state = :state, error = NULL, updatedAt = :now WHERE id = :id")
    suspend fun resetForCaching(id: Long, prefetchMillis: Long, state: String, now: Long)

    @Query("DELETE FROM streams WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(entities = [StreamEntity::class, WatchEntity::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {

    abstract fun streams(): StreamDao

    abstract fun watchHistory(): WatchDao

    companion object {
        private const val DB_NAME = "streambuddy.db"

        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                DB_NAME,
            )
                // Pre-1.0: adding the watch-history table bumps the schema to v2. There is no
                // shipped version to migrate from, so a fresh database is acceptable.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
