package ndika.monitor.ui.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.booster.domain.GameBoosterRepositoryImpl
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.BoostStep

@RequiresApi(Build.VERSION_CODES.N)
class GameBoosterTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: GameBoosterRepositoryImpl

    override fun onCreate() {
        super.onCreate()
        repository = GameBoosterRepositoryImpl(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(Tile.STATE_INACTIVE, "Game Booster")
    }

    override fun onClick() {
        super.onClick()
        updateTileState(Tile.STATE_ACTIVE, "Optimizing...")

        serviceScope.launch {
            var lastProgress: BoostProgress? = null
            repository.executePreGameBoost().collect { progress ->
                lastProgress = progress
            }
            val isSuccess = lastProgress?.step == BoostStep.COMPLETED

            withContext(Dispatchers.Main) {
                val message = if (isSuccess) {
                    "Game Boost Complete! RAM purged & fstrim done."
                } else {
                    lastProgress?.message ?: "Game Boost failed. Check Shizuku."
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                updateTileState(Tile.STATE_INACTIVE, "Game Booster")
            }
        }
    }

    private fun updateTileState(state: Int, label: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.updateTile()
    }
}
