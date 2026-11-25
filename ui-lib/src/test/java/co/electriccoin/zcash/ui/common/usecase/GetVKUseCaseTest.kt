package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetVKUseCaseTest {
    @Test
    fun fullAndIncomingExportOnlyTheirReviewedKeysForSoftwareAndKeystone() {
        for (keystone in listOf(false, true)) {
            val account = account(ufvk = "uview1full", uivk = "uivk1incoming", keystone = keystone)
            val useCase = useCase(MutableStateFlow(listOf(account)))
            val full = assertNotNull(useCase.startReview(account, 0L, VKType.FULL))
            assertNull(useCase.key(full, VKType.FULL))
            assertTrue(useCase.approveFullKey(full))
            assertEquals("uview1full", useCase.key(full, VKType.FULL))
            val incoming = assertNotNull(useCase.startReview(account, 0L, VKType.INCOMING))
            assertEquals("uivk1incoming", useCase.key(incoming, VKType.INCOMING))
            assertNull(useCase.key(incoming, VKType.FULL))
            assertNull(useCase.key(full, VKType.FULL))
        }
    }

    @Test
    fun absentKeysStayUnavailableAndCannotStartAReview() {
        val account = account(ufvk = null, uivk = "uivk1incoming")
        val useCase = useCase(MutableStateFlow(listOf(account)))
        assertEquals(mapOf(VKType.INCOMING to true, VKType.FULL to false), useCase.availability(account))
        assertNull(useCase.startReview(account, 0L, VKType.FULL))
    }

    @Test
    fun accountChangeWalletRenewalAndProcessLossCannotApproveAnOldReview() = runTest {
        val first = account(ufvk = "uview1first", uivk = "uivk1first")
        val other = account(ufvk = "uview1second", uivk = "uivk1second", ownerByte = 1)
        val selected = MutableStateFlow<List<WalletAccount>?>(listOf(first))
        val wallet = WalletWriteAccess()
        val useCase = useCase(selected, wallet)
        val pending = assertNotNull(useCase.startReview(first, wallet.token(), VKType.FULL))
        selected.value = listOf(other)
        assertFalse(useCase.approveFullKey(pending))
        assertNull(useCase.key(pending, VKType.FULL))
        assertNull(useCase.startReview(first, wallet.token(), VKType.FULL))

        val later = assertNotNull(useCase.startReview(other, wallet.token(), VKType.INCOMING))
        assertEquals("uivk1second", useCase.key(later, VKType.INCOMING))
        assertNull(useCase(selected, wallet).key(later, VKType.INCOMING))
        wallet.replaceWallet { }
        assertNull(useCase.key(later, VKType.INCOMING))
    }

    private fun account(
        ufvk: String?,
        uivk: String?,
        keystone: Boolean = false,
        ownerByte: Byte = 0,
    ): WalletAccount {
        val sdk = mockk<Account> {
            every { accountUuid } returns AccountUuid.new(ByteArray(16) { ownerByte })
            every { this@mockk.ufvk } returns ufvk
            every { this@mockk.uivk } returns uivk
        }
        return if (keystone) {
            KeystoneAccount(sdk, "synthetic-unified", "synthetic-transparent", null, null, null, isSelected = true)
        } else {
            ZodlAccount(
                sdk, "synthetic-unified", "synthetic-transparent", "synthetic-sapling", null, null, null, null,
                isSelected = true,
            )
        }
    }

    private fun useCase(
        accounts: MutableStateFlow<List<WalletAccount>?>,
        wallet: WalletWriteAccess = WalletWriteAccess(),
    ) = GetVKUseCase(mockk<AccountDataSource> { every { allAccounts } returns accounts }, wallet)
}
