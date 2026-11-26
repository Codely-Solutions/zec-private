package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletSeedRecovery
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoverFromSeedMismatchUseCaseTest {
    private val wallet = mockk<PersistableWallet>()
    private val access = WalletWriteAccess()
    private val provider = mockk<PersistableWalletProvider>(relaxed = true) {
        coEvery { getPersistableWallet() } returns wallet
    }
    private val sdkRecovery = mockk<WalletSeedRecovery>()
    private val recover = RecoverFromSeedMismatchUseCase(provider, sdkRecovery, access)

    @Test
    fun retryRereadsTheSameWalletAtMostOnceWithoutWritingIt() = runTest {
        coEvery { sdkRecovery.reopen(any(), any()) } coAnswers {
            assertTrue(firstArg<() -> Boolean>()())
            secondArg<() -> Unit>()()
            true
        }

        recover(access.token())
        assertFailsWith<SeedMismatchRecoveryException> { recover(access.token()) }

        coVerify(exactly = 1) { sdkRecovery.reopen(any(), any()) }
        verify(exactly = 1) { provider.retryRead() }
        coVerify(exactly = 0) { provider.store(any()) }
        coVerify(exactly = 0) { provider.storeIfCurrent(any(), any(), any()) }
    }

    @Test
    fun anotherWalletCanRetryAndAnOldSignalCannotReopenIt() = runTest {
        coEvery { sdkRecovery.reopen(any(), any()) } returns false
        val previous = access.token()
        assertFailsWith<SeedMismatchRecoveryException> { recover(previous) }
        access.replaceWallet {}
        recover(previous)
        assertFailsWith<SeedMismatchRecoveryException> { recover(access.token()) }

        coVerify(exactly = 2) { sdkRecovery.reopen(any(), any()) }
        verify(exactly = 0) { provider.retryRead() }
    }

    @Test
    fun noStoredWalletSkipsSdkRetry() = runTest {
        coEvery { provider.getPersistableWallet() } returns null

        recover(access.token())

        coVerify(exactly = 0) { sdkRecovery.reopen(any(), any()) }
    }
}
