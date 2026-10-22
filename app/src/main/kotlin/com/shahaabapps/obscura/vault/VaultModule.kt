package com.shahaabapps.obscura.vault

import com.shahaabapps.obscura.crypto.DeviceBinding
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {
    @Provides
    fun kdfParams(): KdfParams = KdfParams.DEFAULT

    @Provides
    @Singleton
    fun pinBinding(): DeviceBinding = KeystoreBinding()

    @Provides
    @Singleton
    fun biometricGate(): KeystoreGate = KeystoreGate()

    @Provides
    @Singleton
    fun lockoutPolicy(storage: PrefsLockoutStorage, clock: SystemDeviceClock): LockoutPolicy = LockoutPolicy(storage, clock)

    @Provides
    @Singleton
    @Named("decoy")
    fun decoyStore(@ApplicationContext context: Context): VaultStore = VaultStore(File(context.filesDir, "decoy"))

    @Provides
    @Singleton
    @Named("decoy")
    fun decoyPinBinding(): DeviceBinding = KeystoreBinding("obscura.decoy.pin.v1")

    @Provides
    fun vaultSettings(settings: PrefsVaultSettings): VaultSettings = settings
}
