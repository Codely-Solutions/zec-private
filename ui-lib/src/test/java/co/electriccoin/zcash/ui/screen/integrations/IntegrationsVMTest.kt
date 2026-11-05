package co.electriccoin.zcash.ui.screen.integrations

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletRestoringStateUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Gift redemption and settings remain available after merchant integration removal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationsVMTest {
    private val sdkAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() }))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aLedgerAccountHasNoRemovedMerchantNotice() =
        runTest {
            val vm = vm(ledger())
            collect(vm)

            assertNull(vm.state.value?.disabledInfo)
        }

    @Test
    fun aKeystoneAccountHasNoRemovedMerchantNotice() =
        runTest {
            val vm = vm(keystone())
            collect(vm)

            assertNull(vm.state.value?.disabledInfo)
        }

    @Test
    fun theSoftwareWalletHasNoDisabledNotice() =
        runTest {
            val vm = vm(zashi())
            collect(vm)

            assertNull(vm.state.value?.disabledInfo)
        }

    @Test
    fun theSheetKeepsGiftCardAndMoreButNoMerchantEntry() =
        runTest {
            val vm = vm(zashi())
            collect(vm)

            val items = assertNotNull(vm.state.value).items
            assertEquals(2, items.size)
            assertEquals(R.string.integrations_redeemGift, items[0].title.resourceId())
        }

    private fun vm(selectedAccount: WalletAccount) =
        IntegrationsVM(
            getWalletRestoringState =
                mockk<GetWalletRestoringStateUseCase> {
                    every { observe() } returns MutableStateFlow(WalletRestoringState.SYNCING)
                },
            getSelectedWalletAccount =
                mockk<GetSelectedWalletAccountUseCase> {
                    every { observe() } returns MutableStateFlow(selectedAccount)
                },
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            viewOnlyContent = mockk(relaxed = true),
        )

    private fun zashi() =
        ZodlAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            saplingAddress = "s",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
        )

    private fun keystone() =
        KeystoneAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
        )

    private fun ledger() =
        LedgerAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
            deviceIdentity = null,
            zip32AccountIndex = Zip32AccountIndex.new(0L),
        )

    private fun TestScope.collect(vm: IntegrationsVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
    }

    private fun StringResource.resourceId(): Int = (this as StringResource.ByResource).resource
}
