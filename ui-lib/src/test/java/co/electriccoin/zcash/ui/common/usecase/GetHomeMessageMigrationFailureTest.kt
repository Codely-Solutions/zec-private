package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PercentDecimal
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.HomeMessageData
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GetHomeMessageMigrationFailureTest {
    @Test
    fun migrationReadFailureOffersHelpWhileStartingAndDisconnected() = runTest {
        val snapshots = MutableStateFlow<WalletSnapshot?>(null)
        val errors = MutableStateFlow<SynchronizerError?>(null)
        val useCase = GetHomeMessageUseCase(
            walletBackupMessageUseCase = mockk { every { observe() } returns flowOf(WalletBackupData.Unavailable) },
            crashReportingStorageProvider = mockk { every { observe() } returns flowOf(false) },
            walletSnapshotDataSource = mockk { every { observe() } returns snapshots },
            exchangeRateRepository = mockk { every { state } returns MutableStateFlow(ExchangeRateState.OptedOut) },
            accountDataSource = mockk { every { selectedAccount } returns flowOf(null) },
            messageAvailabilityDataSource = mockk(relaxUnitFun = true),
            cache = HomeMessageCacheRepositoryImpl(mockk()),
            isTorEnabledStorageProvider = mockk { every { observe() } returns flowOf(false) },
            migrationHomeMessageSource = mockk { every { observe() } returns flowOf(null) },
            votingHomeMessageSource = mockk {
                every { observeIsCoinholderPollingMessageVisible() } returns flowOf(false)
            },
            synchronizerProvider = mockk {
                every { error } returns errors
            },
        )
        val messages = mutableListOf<HomeMessageData?>()
        backgroundScope.launch { useCase.observe().collect { messages += it } }
        runCurrent()

        val failure = SynchronizerError.MigrationStateUnavailable
        errors.value = failure
        advanceTimeBy(1001)
        assertEquals(HomeMessageData.Error(failure), messages.last())

        snapshots.value = WalletSnapshot(
            status = Synchronizer.Status.DISCONNECTED,
            progress = PercentDecimal(0f),
            synchronizerError = failure,
            isSpendable = false,
            restoringState = WalletRestoringState.INITIATING,
        )
        advanceTimeBy(1001)
        assertEquals(HomeMessageData.Error(failure), messages.last())

        errors.value = null
        advanceTimeBy(1001)
        assertEquals(HomeMessageData.Disconnected, messages.last())
    }
}
