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
import co.electriccoin.zcash.ui.common.provider.TorOnlyRequest
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertNotNull

class FixEphemeralAddressUseCaseTest {
    @Test
    fun failedOrInconclusiveDiscoveryKeepsItsRecoveryAction() = runBlocking {
        for (failure in Failure.entries) {
            var reads = 0
            val tor = mockk<IsTorEnabledStorageProvider> {
                coEvery { get() } coAnswers {
                    val read = ++reads
                    check(read != failure.failedRead) { "Synthetic unavailable Tor setting" }
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
                coEvery { validateAddress("synthetic-address") } returns when (failure) {
                    Failure.STATUS_READ -> mockk<AddressType.Invalid>()
                    else -> AddressType.Transparent
                }
                coEvery { fetchUtxosByAddress(any(), any()) } coAnswers {
                    if (failure == Failure.SDK_THROW) throw IOException("Synthetic failed scan")
                    false
                }
            }
            val status = mockk<GetSwapStatusUseCase> {
                coEvery { this@mockk("synthetic-address") } coAnswers {
                    assertNotNull(currentCoroutineContext()[TorOnlyRequest.Key])
                    SwapQuoteStatusData(isLoading = false, error = IOException("Synthetic unavailable status"))
                }
            }
            val provider = mockk<SynchronizerProvider> {
                coEvery { getSynchronizer() } returns synchronizer
            }
            val navigation = mockk<NavigationRouter>(relaxed = true)
            val recovery = FixEphemeralAddressUseCase(
                synchronizerProvider = provider,
                navigationRouter = navigation,
                accountDataSource = accounts,
                getSwapStatus = status,
                context = mockk(),
                appAccessSession = AppAccessSession().apply { unlock() },
                walletWriteAccess = WalletWriteAccess(),
                isTorEnabled = tor,
            )
            val error = CompletableDeferred<Unit>()
            recovery("synthetic-address", sourceEntryId = null) { error.complete(Unit) }
            withTimeout(5_000) { error.await() }

            coVerify(exactly = failure.expectedReads) { tor.get() }
            val lookups = if (failure == Failure.SDK_FALSE || failure == Failure.SDK_THROW) 1 else 0
            coVerify(exactly = lookups) { synchronizer.fetchUtxosByAddress(any(), any()) }
            val statusRequests = if (failure == Failure.STATUS_READ) 1 else 0
            coVerify(exactly = statusRequests) { status("synthetic-address") }
            verify(exactly = 0) { navigation.back() }
        }
    }

    private enum class Failure(val expectedReads: Int, val failedRead: Int? = null) {
        INITIAL_TOR(1, 1),
        FINAL_TOR(2, 2),
        STATUS_READ(1),
        SDK_FALSE(2),
        SDK_THROW(2),
    }
}
