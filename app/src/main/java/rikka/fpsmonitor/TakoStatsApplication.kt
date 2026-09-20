package rikka.fpsmonitor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class TakoStatsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    companion object {
        lateinit var instance: TakoStatsApplication
            private set
    }
}
