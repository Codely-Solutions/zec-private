package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.EphemeralAddress
import co.electriccoin.zcash.ui.common.provider.EphemeralAddressStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EphemeralAddressRepositoryTest {
    @Test
    fun addressCreationAndInvalidationKeepWalletIdentifiersOutOfDebugLogs() = runTest {
        val uuid = AccountUuid.new(ByteArray(16) { 1 })
        val syntheticAddress = "synthetic-transparent-recipient"
        val synchronizer = mockk<Synchronizer> {
            coEvery { getSingleUseTransparentAddress(uuid) } returns mockk {
                every { address } returns syntheticAddress
                every { gapPosition } returns 1u
                every { gapLimit } returns 20u
            }
        }
        val accounts = mockk<AccountDataSource> {
            coEvery { getSelectedAccount() } returns mockk {
                every { sdkAccount } returns Account.new(uuid)
            }
        }
        val storage = mockk<EphemeralAddressStorageProvider>(relaxed = true)
        val repository = EphemeralAddressRepositoryImpl(
            accounts, mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns synchronizer }, storage
        )

        ShadowLog.clear()
        val address = repository.create()
        repository.invalidate()

        assertEquals(syntheticAddress, address.address)
        coVerify(exactly = 1) { storage.store(uuid, any<EphemeralAddress>()) }
        coVerify(exactly = 1) { storage.remove(uuid) }
        val messages = ShadowLog.getLogs().map { it.msg }
        assertTrue(messages.any { "Stored a new ephemeral address" in it })
        assertTrue(messages.any { "Invalidated ephemeral address" in it })
        assertTrue(messages.none { syntheticAddress in it || "gapPosition" in it || "gapLimit" in it })
    }
}
