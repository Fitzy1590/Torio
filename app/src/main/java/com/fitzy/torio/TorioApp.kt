package com.fitzy.torio

import android.app.Application
import androidx.room.Room
import com.fitzy.torio.data.*
import okhttp3.OkHttpClient

class AppContainer(app: Application) {
    val db = Room.databaseBuilder(app, TorioDatabase::class.java, "torio.db").build()
    val settings = Settings(app)
    val http: OkHttpClient = OkHttpClient()
    val sync: SyncRepository = LocalSyncRepository(db.progressDao())
}

class TorioApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
