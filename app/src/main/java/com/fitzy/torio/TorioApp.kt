package com.fitzy.torio

import android.app.Application
import androidx.room.Room
import com.fitzy.torio.data.*
import okhttp3.OkHttpClient

class AppContainer(app: Application) {
    val db = Room.databaseBuilder(app, TorioDatabase::class.java, "torio.db").build()
    val settings = Settings(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val sync: SyncRepository = LocalSyncRepository(db.progressDao())
    /** bookId -> download percent (0-100) while a download is running. */
    val downloads = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
}

class TorioApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
