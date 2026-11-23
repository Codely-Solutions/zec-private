package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataDataSource
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.MetadataKeyStorageProvider
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataKey
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MetadataRepositoryImpl] passing the data source's save result up to its callers instead of
 * swallowing a failed edit.
 */
class MetadataRepositoryUpdateTest {
    private val owner = AccountUuid.new(ByteArray(16))
    private val ownedAccount = mockk<WalletAccount> { every { sdkAccount } returns Account.new(owner) }
    private val key = mockk<MetadataKey>()
    private val metadataDataSource = mockk<MetadataDataSource>()
    private val repository =
        MetadataRepositoryImpl(
            accountDataSource =
                mockk<AccountDataSource> {
                    every { selectedAccount } returns flowOf(null)
                    every { allAccounts } returns MutableStateFlow<List<WalletAccount>?>(listOf(ownedAccount))
                    coEvery { getSelectedAccount() } returns mockk<WalletAccount>(relaxed = true)
                },
            metadataDataSource = metadataDataSource,
            metadataKeyStorageProvider =
                mockk<MetadataKeyStorageProvider>().also { provider ->
                    coEvery { provider.get(any()) } returns key
                },
            persistableWalletProvider = mockk(),
            simpleSwapAssetProvider = mockk(relaxed = true)
        )

    @Test
    fun aSavedEditReportsTrue() =
        runTest {
            coEvery { metadataDataSource.setTxBookmark("tx1", true, key) } returns true
            coEvery { metadataDataSource.createOrUpdateTxNote("tx1", "note", key) } returns true
            coEvery { metadataDataSource.deleteTxNote("tx1", key) } returns true

            assertTrue(repository.setTxBookmark("tx1", true, owner, 0))
            assertTrue(repository.createOrUpdateTxNote("tx1", "note", owner, 0))
            assertTrue(repository.deleteTxNote("tx1", owner, 0))
        }

    @Test
    fun anUnsavedEditReportsFalse() =
        runTest {
            coEvery { metadataDataSource.setTxBookmark("tx1", false, key) } returns false
            coEvery { metadataDataSource.createOrUpdateTxNote("tx1", "note", key) } returns false
            coEvery { metadataDataSource.deleteTxNote("tx1", key) } returns false
            coEvery {
                metadataDataSource.markTxAsSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(), key)
            } returns false

            assertFalse(repository.setTxBookmark("tx1", false, owner, 0))
            assertFalse(repository.createOrUpdateTxNote("tx1", "note", owner, 0))
            assertFalse(repository.deleteTxNote("tx1", owner, 0))
            assertFalse(markSwap())
        }

    @Test
    fun aThrowingEditReportsFalse() =
        runTest {
            coEvery { metadataDataSource.createOrUpdateTxNote("tx1", "note", key) } throws IOException()

            assertFalse(repository.createOrUpdateTxNote("tx1", "note", owner, 0))
        }

    private suspend fun markSwap() =
        repository.markTxAsSwap(
            depositAddress = "deposit",
            provider = "near",
            origin = mockk<SwapAsset>(relaxed = true),
            destination = mockk<SwapAsset>(relaxed = true),
            totalFees = Zatoshi(0),
            totalFeesUsd = BigDecimal.ZERO,
            amountOutFormatted = BigDecimal.ONE,
            mode = SwapMode.EXACT_INPUT,
            status = SwapStatus.PENDING
        )
}
