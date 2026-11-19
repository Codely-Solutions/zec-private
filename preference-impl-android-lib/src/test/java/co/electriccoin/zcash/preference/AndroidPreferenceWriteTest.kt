package co.electriccoin.zcash.preference

import android.content.SharedPreferences
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidPreferenceWriteTest {
    @Test
    fun failedWritesPropagateAndCanBeRetried() = runTest {
        var durable = false
        var commits = 0
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "commit" -> { commits++; durable }
                else -> proxy
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            check(method.name == "edit")
            editor
        } as SharedPreferences
        val provider = AndroidPreferenceProvider(preferences, StandardTestDispatcher(testScheduler))
        val key = PreferenceKey("synthetic")
        val writes: List<suspend () -> Unit> = listOf(
            { provider.putString(key, "synthetic") },
            { provider.putStringSet(key, setOf("synthetic")) },
            { provider.putLong(key, 1L) },
            { provider.putLong(key, null) },
            { provider.remove(key) }
        )
        writes.forEach { write ->
            durable = false
            assertFailsWith<IOException> { write() }
            durable = true
            write()
        }
        assertEquals(writes.size * 2, commits)
    }
}
