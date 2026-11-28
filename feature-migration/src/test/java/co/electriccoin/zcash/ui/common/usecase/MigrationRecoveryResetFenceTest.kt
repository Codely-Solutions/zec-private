package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationWorker
import com.google.common.util.concurrent.Futures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationRecoveryResetFenceTest {
    @Test
    fun oldWorkerLookupCannotRecreateADeletedWalletWake() = verifyResetDuringLookup(waitsForSdk = false)

    @Test
    fun oldSdkReadCannotRestartADeletedWalletDriver() = verifyResetDuringLookup(waitsForSdk = true)

    @Test
    fun resetInvalidatesWaitingAllAccountsAcrossAConflatedPulse() = verifyResetDuringHydration(allAccounts = null)

    @Test
    fun resetInvalidatesWaitingSelectionAcrossAConflatedPulse() = verifyResetDuringHydration(allAccounts = emptyList())

    private fun verifyResetDuringHydration(allAccounts: List<WalletAccount>?) = runTest {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
        val context = mockk<Context>(relaxed = true)
        val reset = MutableStateFlow(false)
        val accounts = MutableStateFlow(allAccounts)
        val selection = MutableStateFlow<WalletAccount?>(null)
        val provider = mockk<PersistableWalletProvider> {
            coEvery { getPersistableWallet() } returns mockk()
        }
        val recovery = CheckMigrationRecoveryUseCase(
            getOrchardMigrationSdk = mockk(),
            persistableWalletProvider = provider,
            navigationRouter = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            accountDataSource = mockk {
                every { this@mockk.allAccounts } returns accounts
                every { selectedAccount } returns selection
            },
            context = context,
            migrationLiveDriver = mockk(),
            migrationNotifier = mockk(),
            resetPending = reset,
        )
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns workManagerWithAcknowledgedCancellation()
            val oldRecovery = async { recovery() }
            runCurrent()
            assertFalse(oldRecovery.isCompleted)

            reset.value = true
            MigrationScheduler(context).cancelWalletWorkAndWait()
            reset.value = false
            runCurrent()

            assertTrue(oldRecovery.isCompleted)
            val nextRecovery = async { recovery() }
            runCurrent()
            assertFalse(nextRecovery.isCompleted)
            nextRecovery.cancel()
        } finally {
            unmockkObject(WorkManager.Companion)
        }
    }

    private fun verifyResetDuringLookup(waitsForSdk: Boolean) = runTest {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
        val sdkAccount = AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-4000-8000-000000000151"))
        val owner = sdkAccount.accountUuid.toStorageKeyId()
        val account = mockk<WalletAccount> {
            every { this@mockk.sdkAccount } returns sdkAccount
            every { isSpendable } returns true
            every { isSelected } returns true
        }
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(account))
        val context = mockk<Context>(relaxed = true)
        val reset = MutableStateFlow(false)
        val manager = workManagerWithAcknowledgedCancellation()
        val inProgress = MigrationState.InProgress(mockk(relaxed = true))
        val workerState = CompletableDeferred<MigrationWorkerRunState>()
        val sdkState = CompletableDeferred<MigrationState>()
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { getMigrationStateUnreconciled() } coAnswers { sdkState.await() }
        }
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val oldWakes = mutableListOf<String>()
        val recovery = CheckMigrationRecoveryUseCase(
            getOrchardMigrationSdk = mockk { coEvery { this@mockk(owner) } returns sdk },
            persistableWalletProvider = mockk { coEvery { getPersistableWallet() } returns mockk() },
            navigationRouter = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            accountDataSource = mockk { every { allAccounts } returns accounts },
            context = context,
            getWorkerRunState = { workerState.await() },
            scheduleNow = { key, _ -> oldWakes += key },
            migrationLiveDriver = driver,
            migrationNotifier = mockk(),
            resetPending = reset,
        )
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            if (!waitsForSdk) sdkState.complete(inProgress)
            val oldRecovery = async { recovery() }
            runCurrent()
            assertFalse(oldRecovery.isCompleted)
            val version = MigrationScheduler.wakeVersion(owner)

            reset.value = true
            accounts.value = null
            MigrationScheduler(context).cancelWalletWorkAndWait()
            reset.value = false
            assertTrue(MigrationScheduler.wakeVersion(owner) > version)
            if (waitsForSdk) sdkState.complete(inProgress)
            workerState.complete(MigrationWorkerRunState.ABSENT)
            oldRecovery.await()

            assertTrue(oldWakes.isEmpty())
            verify(exactly = if (waitsForSdk) 0 else 1) { driver.startIfNotRunning(owner) }
        } finally {
            unmockkObject(WorkManager.Companion)
        }
    }

    private fun workManagerWithAcknowledgedCancellation(): WorkManager = mockk {
        every { cancelAllWorkByTag(MigrationWorker::class.java.name) } returns mockk<Operation> {
            every { result } returns Futures.immediateFuture(Operation.SUCCESS)
        }
    }
}
