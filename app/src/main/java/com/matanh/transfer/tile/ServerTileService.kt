package com.matanh.transfer.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.matanh.transfer.R
import com.matanh.transfer.util.ServerRemoteControl

/** Quick Settings tile: tap to start/stop the Transfer server. */
class ServerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        ServerRemoteControl.toggle(applicationContext)
        // Pref update arrives async from service; optimistic flip for snappy UI.
        qsTile?.let { tile ->
            val nextActive = tile.state != Tile.STATE_ACTIVE
            tile.state = if (nextActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(
                if (nextActive) R.string.qs_tile_stop else R.string.qs_tile_start
            )
            tile.updateTile()
        }
        // Re-sync shortly after service updates prefs.
        qsTile?.let {
            it.subtitle = null
        }
        android.os.Handler(mainLooper).postDelayed({ refreshTile() }, 800)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val active = ServerRemoteControl.isServerActive(applicationContext)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (active) R.string.qs_tile_stop else R.string.qs_tile_start)
        tile.contentDescription = tile.label
        tile.updateTile()
    }
}
