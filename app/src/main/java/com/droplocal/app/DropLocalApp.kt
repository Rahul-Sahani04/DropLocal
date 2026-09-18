package com.droplocal.app

import android.app.Application
import com.droplocal.app.nearby.NearbyManager
import com.droplocal.app.storage.HistoryStore
import com.droplocal.app.transfer.TransferRepository

class DropLocalApp : Application() {
    lateinit var nearby: NearbyManager
    lateinit var transfers: TransferRepository
    lateinit var history: HistoryStore

    override fun onCreate() {
        super.onCreate()
        history = HistoryStore(this)
        transfers = TransferRepository(history)
        nearby = NearbyManager(this, transfers)
    }
}
