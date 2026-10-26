package com.shahaabapps.obscura

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.vault.VaultEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Holds the vault open for the length of a test, the way the app itself does during long work.
 *
 * With no screen of its own, a test runs with the app in the background, where auto-lock is
 * waiting to close the vault after its delay. A test that seals a thousand files or encodes a
 * video outruns that and has the vault shut under it, which looks like a failure in whatever it
 * was doing. The app never works this way: an import, an export or a GIF takes a hold first, so
 * taking one here matches it rather than papering over it.
 *
 * Use it on tests that drive the vault directly and take a while. Tests about locking itself
 * must not use it.
 */
class KeepVaultOpen : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
            val autoLock = EntryPointAccessors
                .fromApplication(context, VaultEntryPoint::class.java)
                .autoLock()
            autoLock.hold()
            try {
                base.evaluate()
            } finally {
                autoLock.release()
            }
        }
    }
}
