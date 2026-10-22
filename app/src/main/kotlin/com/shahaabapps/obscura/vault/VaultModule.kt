package com.shahaabapps.obscura.vault

import com.shahaabapps.obscura.crypto.DeviceBinding
import com.shahaabapps.obscura.crypto.KdfParams
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.crypto.KeystoreGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
}
