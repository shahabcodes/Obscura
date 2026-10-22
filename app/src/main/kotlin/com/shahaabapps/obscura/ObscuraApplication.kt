package com.shahaabapps.obscura

import android.app.Application
import com.shahaabapps.obscura.vault.AutoLock
import com.shahaabapps.obscura.vault.PanicLock
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ObscuraApplication : Application() {

    @Inject
    lateinit var autoLock: AutoLock

    @Inject
    lateinit var panicLock: PanicLock

    override fun onCreate() {
        super.onCreate()
        autoLock.start()
        panicLock.start()
    }
}
