package com.shahaabapps.obscura

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shahaabapps.obscura.crypto.KeystoreBinding
import com.shahaabapps.obscura.importer.ImportOptions
import com.shahaabapps.obscura.importer.ImportSource
import com.shahaabapps.obscura.vault.VaultEntryPoint
import com.shahaabapps.obscura.vault.VaultState
import dagger.hilt.android.EntryPointAccessors
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Panic lock locks at once, even in the middle of an import that holds the vault open. */
@RunWith(AndroidJUnit4::class)
class PanicLockTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, VaultEntryPoint::class.java)

    private fun wipe() = instrumentation.runOnMainSync {
        entry.importManager().cancel()
        entry.session().lock()
        entry.store().destroy()
        KeystoreBinding().delete()
        context.getSharedPreferences("obscura.lockout", Context.MODE_PRIVATE).edit().clear().commit()
        entry.session().reload()
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    @Before
    fun setUp() {
        wipe()
        runBlocking { entry.session().create("lantern orbit velvet canyon mosaic".toCharArray()) }
    }

    @After
    fun tearDown() {
        entry.panicLock().acknowledge()
        entry.importManager().reset()
        wipe()
    }

    @Test
    fun panicLocksImmediately() {
        entry.panicLock().trigger()
        assertTrue(waitFor { entry.session().state.value is VaultState.Locked })
        // The lock screen is told to confirm the panic lock, once.
        assertTrue(entry.panicLock().panicked.value)
        entry.panicLock().acknowledge()
        assertTrue(!entry.panicLock().panicked.value)
    }

    @Test
    fun noPanicNoticeWhenAlreadyLocked() {
        entry.session().lock()
        entry.panicLock().trigger()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(!entry.panicLock().panicked.value)
    }

    @Test
    fun panicOverridesARunningImport() {
        val slow = object : ImportSource {
            override val displayName = "slow.bin"
            override val mimeType = "application/octet-stream"
            override val sizeBytes = 10L * 64 * 1024
            override val lastModified: Long? = null
            override fun open(): InputStream = object : InputStream() {
                var left = 10 * 64 * 1024
                override fun read(): Int = if (left-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    Thread.sleep(500)
                    val n = minOf(len, 64 * 1024, left)
                    left -= n
                    return n
                }
            }
            override fun deleteOriginal() = false
        }
        entry.importManager().start(listOf(slow), ImportOptions(), startService = false)
        Thread.sleep(600)
        entry.panicLock().trigger()
        assertTrue("locked despite the import's hold", waitFor { entry.session().state.value is VaultState.Locked })
        assertTrue("the import was cancelled", waitFor { entry.importManager().progress.value.cancelled })
    }
}
