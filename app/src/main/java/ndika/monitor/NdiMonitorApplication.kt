package ndika.monitor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class NdiMonitorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    companion object {
        lateinit var instance: NdiMonitorApplication
            private set
    }
}
