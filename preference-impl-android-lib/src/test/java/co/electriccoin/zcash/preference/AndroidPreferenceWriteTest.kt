package co.electriccoin.zcash.preference

import android.content.SharedPreferences
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun clearObserversReadAfterTheCommitAndTheCallerReceivesItsResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        listOf(true, false).forEach { commitSucceeds ->
            val preferences = ClearedWithoutListeners(commitSucceeds)
            val provider = AndroidPreferenceProvider(preferences.preferences, dispatcher)
            val first = mutableListOf<String?>()
            val second = mutableListOf<String?>()
            val key = PreferenceKey("synthetic")
            val firstObserver = backgroundScope.launch(dispatcher) { provider.observe(key).collect { first += it } }
            val secondObserver = backgroundScope.launch(dispatcher) { provider.observe(key).collect { second += it } }
            runCurrent()
            assertEquals(listOf<String?>("before"), first)
            assertEquals(listOf<String?>("before"), second)

            assertEquals(commitSucceeds, provider.clearPreferences())
            runCurrent()

            assertEquals(listOf("before", null), first)
            assertEquals(listOf("before", null), second)
            firstObserver.cancel()
            secondObserver.cancel()
        }
    }

    @Test
    fun clearCommittedDuringCallerCancellationStillInvalidatesObservers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = ClearedWithoutListeners(true)
        val provider = AndroidPreferenceProvider(preferences.preferences, dispatcher)
        val observed = mutableListOf<String?>()
        backgroundScope.launch(dispatcher) { provider.observe(PreferenceKey("synthetic")).collect { observed += it } }
        runCurrent()
        val clearing = launch(dispatcher, start = CoroutineStart.LAZY) { provider.clearPreferences() }
        preferences.onCommit = { clearing.cancel() }

        clearing.start()
        clearing.join()
        runCurrent()

        assertEquals(listOf("before", null), observed)
    }

    @Test
    fun exceptionalClearInvalidatesChangedMemoryAndCanBeRetried() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = ClearedWithoutListeners(true)
        val provider = AndroidPreferenceProvider(preferences.preferences, dispatcher)
        val observed = mutableListOf<String?>()
        backgroundScope.launch(dispatcher) { provider.observe(PreferenceKey("synthetic")).collect { observed += it } }
        runCurrent()
        preferences.onCommit = { throw IllegalStateException("Synthetic commit failure after memory changed") }

        assertFailsWith<IllegalStateException> { provider.clearPreferences() }
        runCurrent()
        assertEquals(listOf("before", null), observed)

        preferences.onCommit = {}
        assertEquals(true, provider.clearPreferences())
        assertNull(provider.getString(PreferenceKey("synthetic")))
    }

    @Test
    fun clearWaitsForTheWriteHoldingTheMutex() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = ClearedWithoutListeners(true)
        val provider = AndroidPreferenceProvider(preferences.preferences, dispatcher)
        val key = PreferenceKey("synthetic")
        val order = mutableListOf<String>()
        preferences.onPutCommit = {
            order += "put started"
            // Start an overlapping clear inside the synchronous commit, while put still owns the mutex.
            launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { provider.clearPreferences() }
            order += "put finished"
        }
        preferences.onCommit = { order += "clear committed" }

        provider.putString(key, "replacement")
        runCurrent()

        assertEquals(listOf("put started", "put finished", "clear committed"), order)
        assertNull(provider.getString(key))
    }

    /** Models AndroidX clear-only commits, which skip listener callbacks and update memory on false. */
    private class ClearedWithoutListeners(private val commitSucceeds: Boolean) {
        private var storedValue: String? = "before"
        var onCommit: () -> Unit = {}
        var onPutCommit: () -> Unit = {}

        private fun editor(): SharedPreferences.Editor {
            var updatedValue: String? = null
            var clearing = false
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "clear" -> {
                        clearing = true
                        proxy
                    }
                    "putString" -> {
                        updatedValue = checkNotNull(arguments)[1] as String?
                        proxy
                    }
                    "commit" -> {
                        if (!clearing) onPutCommit()
                        storedValue = updatedValue
                        if (clearing) onCommit()
                        commitSucceeds
                    }
                    else -> error("Unexpected editor method")
                }
            } as SharedPreferences.Editor
        }

        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editor()
                "getString" -> storedValue
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                else -> error("Unexpected preferences method")
            }
        } as SharedPreferences
    }
}
