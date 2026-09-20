package ndika.monitor.ui.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import ndika.monitor.overlay.StandaloneOverlayService
import ndika.monitor.util.PreferenceManager

@RequiresApi(Build.VERSION_CODES.N)
class FpsTileService : TileService() {

    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = preferenceManager.isServiceRunning
        if (isRunning) {
            StandaloneOverlayService.stop(this)
            preferenceManager.isServiceRunning = false
        } else {
            StandaloneOverlayService.start(this)
            preferenceManager.isServiceRunning = true
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = preferenceManager.isServiceRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
