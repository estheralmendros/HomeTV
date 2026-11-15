package com.hometv.tv.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.ScriptedApi
import com.hometv.tv.testing.testContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Coil allows one active DiskCache per directory: a server change rebuilds the loader on the same caches. */
@RunWith(AndroidJUnit4::class)
class ImageLoaderSharingTest {
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aServerChangeBuildsANewLoaderThatSharesTheMemoryAndDiskCaches() {
        val container = testContainer(ScriptedApi(), scope)
        val first = container.imageLoader(context)
        assertSame(first, container.imageLoader(context))
        runBlocking { container.store.setServerUrl("https://other.ts.net") }
        val second = container.imageLoader(context)
        assertNotSame(first, second)
        assertSame(first.diskCache, second.diskCache)
        assertSame(first.memoryCache, second.memoryCache)
    }
}
