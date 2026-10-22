package com.shahaabapps.obscura

import android.app.Application
import com.shahaabapps.obscura.vault.AutoLock
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ObscuraApplication : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    override fun onCreate() {
        super.onCreate()
        autoLock.start()
    }
}
