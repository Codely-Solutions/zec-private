package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptInExchangeRateUseCaseTest {
    @Test
    fun acknowledgedChoiceQueuesOnlyItsOwnersBack() = runTest {
        val pending = PendingNavigation()
        val router = mockk<NavigationRouter>(relaxed = true) {
            every { pendingNavigation } returns pending
        }
        val provider = mockk<IsExchangeRateEnabledStorageProvider>(relaxed = true)
        val useCase = OptInExchangeRateUseCase(router, provider)

        useCase(true, FiatCurrency("EUR"), "settings")

        coVerify(exactly = 1) { provider.store(true, FiatCurrency("EUR")) }
        verify(exactly = 0) { router.back() }
        assertEquals(NavigationCommand.Back, pending.commands.value.getValue("settings").buildCommand())
    }

    @Test
    fun failedChoiceKeepsTheEntryOpenAndRetryDoesNotChangeTheUnspecifiedCurrency() = runTest {
        val pending = PendingNavigation()
        val router = mockk<NavigationRouter>(relaxed = true) {
            every { pendingNavigation } returns pending
        }
        val provider = mockk<IsExchangeRateEnabledStorageProvider> {
            coEvery { store(false, null) } throws IOException("Synthetic save failure") andThen Unit
        }
        val useCase = OptInExchangeRateUseCase(router, provider)

        assertFailsWith<IOException> { useCase(false, sourceEntryId = "onboarding") }
        assertTrue(pending.commands.value.isEmpty())
        useCase(false, sourceEntryId = "onboarding")
        coVerify(exactly = 2) { provider.store(false, null) }
        assertEquals(NavigationCommand.Back, pending.commands.value.getValue("onboarding").buildCommand())
    }
}
