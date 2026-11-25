package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class FixEphemeralAddressUseCaseTest {
    @Test
    fun bothTorReadsRejectRecoveryWithoutNavigationOrNetworkSubmission() = runBlocking {
        for (failureAt in 1..2) {
            var reads = 0
            val tor = mockk<IsTorEnabledStorageProvider> {
                coEvery { get() } coAnswers {
                    check(++reads != failureAt) { "Synthetic unavailable Tor setting" }
                    true
                }
            }
            val account = mockk<WalletAccount> {
                every { isSelected } returns true
                every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
            }
            val accounts = mockk<AccountDataSource> {
                every { allAccounts } returns MutableStateFlow(listOf(account))
            }
            val synchronizer = mockk<Synchronizer> {
                coEvery { validateAddress("synthetic-address") } returns AddressType.Transparent
            }
            val provider = mockk<SynchronizerProvider> {
                coEvery { getSynchronizer() } returns synchronizer
            }
            val navigation = mockk<NavigationRouter>(relaxed = true)
            val recovery = FixEphemeralAddressUseCase(
                synchronizerProvider = provider,
                navigationRouter = navigation,
                accountDataSource = accounts,
                getSwapStatus = mockk(),
                context = mockk(),
                appAccessSession = AppAccessSession().apply { unlock() },
                walletWriteAccess = WalletWriteAccess(),
                isTorEnabled = tor,
            )
            val error = CompletableDeferred<Unit>()
            recovery("synthetic-address", sourceEntryId = null) { error.complete(Unit) }
            withTimeout(5_000) { error.await() }

            coVerify(exactly = failureAt) { tor.get() }
            coVerify(exactly = failureAt - 1) { provider.getSynchronizer() }
            coVerify(exactly = 0) { synchronizer.fetchUtxosByAddress(any(), any()) }
            verify(exactly = 0) { navigation.back() }
        }
    }
}
