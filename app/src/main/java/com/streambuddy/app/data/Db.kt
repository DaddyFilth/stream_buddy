package com.streambuddy.app.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "streams")
data class StreamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val url: String,
    val cacheKey: String, val cachedBytes: Long = 0,
    val fullyCached: Boolean = false, val addedAt: Long = System.currentTimeMillis()
)

@Dao interface StreamDao {
    @Query("SELECT * FROM streams ORDER BY addedAt DESC") fun all(): kotlinx.coroutines.flow.Flow<List<StreamEntity>>
    @Insert suspend fun insert(s: StreamEntity): Long
    @Update suspend fun update(s: StreamEntity)
    @Delete suspend fun delete(s: StreamEntity)
}

@Database(entities = [StreamEntity::class], version = 1)
abstract class AppDb : RoomDatabase() {
    abstract fun streams(): StreamDao
    companion object {
        @Volatile private var I: AppDb? = null
        fun get(c: Context): AppDb = I ?: synchronized(this) {
            I ?: Room.databaseBuilder(c, AppDb::class.java, "streambuddy.db").build().also { I = it }
        }
    }
}
