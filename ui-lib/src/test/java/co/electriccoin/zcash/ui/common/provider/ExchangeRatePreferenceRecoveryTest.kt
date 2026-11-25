package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeRatePreferenceRecoveryTest {
    @Test
    fun pairedConversionChoiceUsesOneCommitAndKeepsTheExistingPreferenceKeys() = runTest {
        val legacy = PreferenceKey("EXCHANGE_RATE_OPTED_IN")
        val enabled = PreferenceKey("EXCHANGE_RATE_OPTED_IN_2")
        val currency = PreferenceKey("preferred_fiat_currency")
        val preferences = mockk<PreferenceProvider> {
            coEvery { hasKey(legacy) } returns false
            coEvery { putStringsWithRollback(any()) } returns Unit
        }
        val holder = mockk<StandardPreferenceProvider> {
            coEvery { this@mockk() } returns preferences
        }
        val provider = IsExchangeRateEnabledStorageProviderImpl(holder)

        provider.store(true, FiatCurrency("EUR"))
        provider.store(false, null)

        coVerify(exactly = 1) { preferences.putStringsWithRollback(mapOf(enabled to "true", currency to "EUR")) }
        coVerify(exactly = 1) { preferences.putStringsWithRollback(mapOf(enabled to "false")) }
        coVerify(exactly = 0) { preferences.putString(enabled, any()) }
        coVerify(exactly = 0) { preferences.putString(currency, any()) }
    }

    @Test
    fun failedLegacyRemovalLeavesTheCurrentRatePreferenceUsable() = runTest {
        val legacy = PreferenceKey("EXCHANGE_RATE_OPTED_IN")
        val current = PreferenceKey("EXCHANGE_RATE_OPTED_IN_2")
        val preferences = mockk<PreferenceProvider> {
            coEvery { hasKey(legacy) } returns true
            coEvery { remove(legacy) } throws IOException("Synthetic disk write failure")
            every { observe(current) } returns flowOf("true")
            coEvery { getString(current) } returns "true"
        }
        val holder = mockk<StandardPreferenceProvider> {
            coEvery { this@mockk() } returns preferences
        }
        val provider = IsExchangeRateEnabledStorageProviderImpl(holder)

        val observed = mutableListOf<Boolean?>()
        backgroundScope.launch { provider.observe().collect { observed += it } }
        runCurrent()
        assertEquals(listOf<Boolean?>(true), observed)
        assertEquals(true, provider.get())
        coVerify(exactly = 2) { preferences.remove(legacy) }
    }
}
