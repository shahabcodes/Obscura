package com.shahaabapps.obscura.vault

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets non-injected code (instrumentation tests, future widgets) reach the app's singletons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VaultEntryPoint {
    fun session(): VaultSession
    fun store(): VaultStore
    fun autoLock(): AutoLock
    fun importManager(): com.shahaabapps.obscura.importer.ImportManager
    fun panicLock(): PanicLock
    fun settings(): VaultSettings
    fun backupManager(): com.shahaabapps.obscura.backup.BackupManager
    fun exportManager(): com.shahaabapps.obscura.export.ExportManager
    fun shareGrants(): com.shahaabapps.obscura.export.ShareGrants
    fun gallerySettings(): com.shahaabapps.obscura.ui.gallery.GallerySettingsStore
}
