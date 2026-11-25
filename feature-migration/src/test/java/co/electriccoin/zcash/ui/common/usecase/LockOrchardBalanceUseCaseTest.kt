package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.OrchardMigrationSdk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockOrchardBalanceUseCaseTest {
    @Test
    fun ownershipIsCheckedWhenTheNativeLockBecomesAvailable() = runTest {
        val owner = "synthetic-account"
        val accessingSdk = CompletableDeferred<Unit>()
        val sdkLookup = CompletableDeferred<OrchardMigrationSdk>()
        val resolveSdk = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk(owner) } coAnswers {
                accessingSdk.complete(Unit)
                sdkLookup.await()
            }
        }
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val lock = LockOrchardBalanceUseCase(resolveSdk)
        var ownsEntry = true

        val pending = async { lock(owner) { ownsEntry } }
        accessingSdk.await()
        ownsEntry = false
        sdkLookup.complete(sdk)
        assertFalse(pending.await())
        coVerify(exactly = 0) { sdk.lockRemainingOrchardBalance() }

        ownsEntry = true
        assertTrue(lock(owner) { ownsEntry })
        coVerify(exactly = 1) { sdk.lockRemainingOrchardBalance() }
    }
}
