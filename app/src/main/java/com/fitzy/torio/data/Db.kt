package com.fitzy.torio.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    val codec: String = "",
    val bitrateKbps: Int = 0,
    val sizeBytes: Long = 0,
    val downloaded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
)

/** One playable file (or chapter) of a book. localPath wins over remoteUrl when set. */
@Entity(tableName = "tracks", primaryKeys = ["bookId", "idx"])
data class TrackEntity(
    val bookId: String,
    val idx: Int,
    val title: String,
    val remoteUrl: String,
    val localPath: String? = null,
    val durationMs: Long = 0,
)

/** Sync-ready: updatedAt + deviceId let Phase 2 resolve conflicts last-writer-wins. */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val totalPositionMs: Long = 0,
    val totalDurationMs: Long = 0,
    val percent: Int = 0,
    val finished: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val deviceId: String = "local",
)

@Dao
interface BookDao {
    @Upsert suspend fun upsertBook(b: BookEntity)
    @Upsert suspend fun upsertTracks(t: List<TrackEntity>)
    @Query("SELECT * FROM books WHERE id=:id") suspend fun book(id: String): BookEntity?
    @Query("SELECT * FROM tracks WHERE bookId=:id ORDER BY idx") suspend fun tracks(id: String): List<TrackEntity>
    @Query("SELECT * FROM books ORDER BY addedAt DESC") fun books(): Flow<List<BookEntity>>
    @Query("UPDATE tracks SET localPath=:path WHERE bookId=:id AND idx=:idx")
    suspend fun setLocalPath(id: String, idx: Int, path: String)
    @Query("UPDATE books SET coverUrl=:cover, author=CASE WHEN author='' THEN :author ELSE author END WHERE id=:id")
    suspend fun setCover(id: String, cover: String?, author: String)
    @Query("UPDATE books SET downloaded=:d WHERE id=:id") suspend fun setDownloaded(id: String, d: Boolean)
    @Query("UPDATE tracks SET durationMs=:ms WHERE bookId=:id AND idx=:idx")
    suspend fun setDuration(id: String, idx: Int, ms: Long)
    @Query("DELETE FROM books WHERE id=:id") suspend fun deleteBook(id: String)
    @Query("DELETE FROM tracks WHERE bookId=:id") suspend fun deleteTracks(id: String)
}

@Dao
interface ProgressDao {
    @Upsert suspend fun save(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE bookId=:id") suspend fun get(id: String): ProgressEntity?
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 1") suspend fun latest(): ProgressEntity?
    @Query("SELECT * FROM progress") fun all(): Flow<List<ProgressEntity>>
    @Query("SELECT * FROM progress WHERE updatedAt > :since") suspend fun changedSince(since: Long): List<ProgressEntity>
    @Query("DELETE FROM progress WHERE bookId=:id") suspend fun delete(id: String)
}

@Database(entities = [BookEntity::class, TrackEntity::class, ProgressEntity::class], version = 1, exportSchema = true)
abstract class TorioDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
}

/** Phase 2 hook: a Windows-sync implementation plugs in here. Phase 1 only has the local one. */
interface SyncRepository {
    suspend fun saveProgress(p: ProgressEntity)
    suspend fun latestProgress(): ProgressEntity?
    suspend fun progressFor(bookId: String): ProgressEntity?
    suspend fun pendingChanges(sinceMs: Long): List<ProgressEntity>
    suspend fun applyRemote(p: ProgressEntity)
}

class LocalSyncRepository(private val dao: ProgressDao) : SyncRepository {
    override suspend fun saveProgress(p: ProgressEntity) = dao.save(p)
    override suspend fun latestProgress() = dao.latest()
    override suspend fun progressFor(bookId: String) = dao.get(bookId)
    override suspend fun pendingChanges(sinceMs: Long) = dao.changedSince(sinceMs)
    override suspend fun applyRemote(p: ProgressEntity) {
        val cur = dao.get(p.bookId)
        if (cur == null || p.updatedAt > cur.updatedAt) dao.save(p)
    }
}

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("torio", Context.MODE_PRIVATE)
    var torboxKey: String
        get() = sp.getString("torbox_key", "") ?: ""
        set(v) = sp.edit().putString("torbox_key", v.trim()).apply()
    var abbUrl: String
        get() = sp.getString("abb_url", "https://audiobookbay.lu") ?: "https://audiobookbay.lu"
        set(v) = sp.edit().putString("abb_url", v.trim().trimEnd('/')).apply()
    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()
    var dspOn: Boolean
        get() = sp.getBoolean("dsp", true)
        set(v) = sp.edit().putBoolean("dsp", v).apply()
    var autoResume: Boolean
        get() = sp.getBoolean("auto_resume", true)
        set(v) = sp.edit().putBoolean("auto_resume", v).apply()
    var lastBookId: String?
        get() = sp.getString("last_book", null)
        set(v) = sp.edit().putString("last_book", v).apply()
}
