package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.UnifiedAddressRequest
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GetGiftCardDestinationAddressUseCase] requests an Orchard-only address for the reviewed account.
 */
class GetGiftCardDestinationAddressUseCaseTest {
    private val account = mockk<Account>()

    private val walletAccount = mockk<WalletAccount> { every { sdkAccount } returns account }

    private val mainSynchronizer = mockk<Synchronizer>()

    private val synchronizerProvider =
        mockk<SynchronizerProvider> { every { synchronizer } returns MutableStateFlow(mainSynchronizer) }

    @Test
    fun theDestinationIsTheReviewedAccountsOrchardOnlyAddress() =
        runTest {
            coEvery { mainSynchronizer.getCustomUnifiedAddress(account, UnifiedAddressRequest.Orchard) } returns
                ORCHARD_ADDRESS

            val destination = GetGiftCardDestinationAddressUseCase(
                synchronizerProvider,
                AppAccessSession().apply { unlock() },
                WalletWriteAccess(),
                WalletTransportAccess(),
            )(walletAccount)()

            assertEquals(ORCHARD_ADDRESS, destination.address)
            assertTrue(destination.permitsSubmission())
            coVerify(exactly = 1) { mainSynchronizer.getCustomUnifiedAddress(any(), any()) }
        }

    private companion object {
        const val ORCHARD_ADDRESS = "u1orchardonly"
    }
}
