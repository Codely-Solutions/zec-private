package co.electriccoin.zcash.ui.screen.qrcode

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * The address QR screen: it shows the selected account's address of the requested type, shares that address
 * as a QR with the ZEC share texts and the centre icon matching the address kind, copies it to the clipboard,
 * titles a Keystone account's QR as Keystone and a view-only account's addresses with the view-only copy,
 * and stays loading for an address the account does not have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrCodeVMTest {
    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unifiedAddressSharesAShieldedQrWithTheZecTexts() =
        runTest(dispatcher) {
            val shareQR = mockk<ShareQRUseCase>(relaxed = true)
            val vm = startedVm(addressType = ReceiveAddressType.Unified, shareQR = shareQR)

            val state = assertIs<QrCodeState.Prepared>(vm.state.value)
            assertIs<WalletAddress.Unified>(state.walletAddress)
            assertEquals(UNIFIED, state.walletAddress.address)

            state.onQrCodeShare(UNIFIED)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                shareQR(
                    qrData = UNIFIED,
                    shareText = stringRes(R.string.qr_code_share_chooser_text, CURRENCY_TICKER),
                    sharePickerText = stringRes(R.string.qr_code_share_chooser_title, CURRENCY_TICKER),
                    filenamePrefix = "zcash_address_qr_",
                    centerIcon = R.drawable.ic_zec_qr_shielded,
                    isAllowed = any(),
                )
            }
        }

    @Test
    fun transparentAddressSharesWithTheTransparentIcon() =
        runTest(dispatcher) {
            val shareQR = mockk<ShareQRUseCase>(relaxed = true)
            val vm = startedVm(addressType = ReceiveAddressType.Transparent, shareQR = shareQR)

            val state = assertIs<QrCodeState.Prepared>(vm.state.value)
            assertIs<WalletAddress.Transparent>(state.walletAddress)

            state.onQrCodeShare(TRANSPARENT)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                shareQR(
                    qrData = TRANSPARENT,
                    shareText = any(),
                    sharePickerText = any(),
                    filenamePrefix = any(),
                    centerIcon = R.drawable.ic_zec_qr_transparent,
                    isAllowed = any(),
                )
            }
        }

    @Test
    fun stagedReceiveQrRetainsTheDisplayedPayloadAndOriginalGrant() = runTest(dispatcher) {
        val access = AppAccessSession().apply { unlock() }
        val share = mockk<ShareQRUseCase> {
            coEvery { this@mockk(any(), any(), any(), any(), any(), any()) } coAnswers {
                val allowed = arg<() -> Boolean>(5)
                assertEquals(UNIFIED, firstArg())
                assertTrue(allowed())
                access.lock()
                access.unlock()
                assertFalse(allowed())
                false
            }
        }
        val vm = startedVm(shareQR = share, access = access)
        val state = assertIs<QrCodeState.Prepared>(vm.state.value)
        state.onQrCodeShare("other-address")
        state.onQrCodeShare(UNIFIED)
        advanceUntilIdle()
        coVerify(exactly = 1) { share(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun copyPutsTheAddressOnTheClipboard() =
        runTest(dispatcher) {
            val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
            val vm = startedVm(copyToClipboard = copyToClipboard)

            assertIs<QrCodeState.Prepared>(vm.state.value).onAddressCopy(UNIFIED)

            verify(exactly = 1) { copyToClipboard(UNIFIED) }
        }

    @Test
    fun retainedCopyRefusesChangedPayloadAndRevokedOwnership() = runTest(dispatcher) {
        val clipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
        val access = AppAccessSession().apply { unlock() }
        val ownership = WalletWriteAccess()
        val original = zashiAccount()
        val account = kotlinx.coroutines.flow.MutableStateFlow<WalletAccount?>(original)
        val vm = startedVm(
            copyToClipboard = clipboard, access = access, accountFlow = account, ownership = ownership
        )
        val before = assertIs<QrCodeState.Prepared>(vm.state.value)
        before.onAddressCopy("other")
        before.onAddressCopy(UNIFIED)

        access.lock()
        access.unlock()
        before.onAddressCopy(UNIFIED)
        account.value = original.copy(unifiedAddress = "new-address")
        advanceUntilIdle()
        val after = assertIs<QrCodeState.Prepared>(vm.state.value)
        before.onAddressCopy(UNIFIED)
        after.onAddressCopy("new-address")
        ownership.beginReset()
        after.onAddressCopy("new-address")

        verify(exactly = 1) { clipboard(UNIFIED) }
        verify(exactly = 1) { clipboard("new-address") }
        verify(exactly = 0) { clipboard("other") }
    }

    @Test
    fun keystoneAccountMarksTheQrAsKeystone() =
        runTest(dispatcher) {
            val zodl = startedVm(account = zashiAccount())
            val keystone = startedVm(account = keystoneAccount())

            assertEquals(
                stringRes(R.string.accounts_zashi_shieldedAddress),
                assertIs<QrCodeState.Prepared>(zodl.state.value).title
            )
            assertEquals(
                stringRes(R.string.qr_code_wallet_address_shielded_keystone),
                assertIs<QrCodeState.Prepared>(keystone.state.value).title
            )
        }

    @Test
    fun aViewOnlyAccountTitlesItsAddressesWithTheViewOnlyCopy() =
        runTest(dispatcher) {
            val viewOnlyContent =
                mockk<ViewOnlyContent> {
                    every { receiveShieldedTitle } returns VIEW_ONLY_SHIELDED
                    every { receiveTransparentTitle } returns VIEW_ONLY_TRANSPARENT
                }
            val shielded =
                startedVm(
                    account = viewOnlyAccount(),
                    addressType = ReceiveAddressType.Unified,
                    viewOnlyContent = viewOnlyContent
                )
            val transparent =
                startedVm(
                    account = viewOnlyAccount(),
                    addressType = ReceiveAddressType.Transparent,
                    viewOnlyContent = viewOnlyContent
                )

            assertEquals(VIEW_ONLY_SHIELDED, assertIs<QrCodeState.Prepared>(shielded.state.value).title)
            assertEquals(VIEW_ONLY_TRANSPARENT, assertIs<QrCodeState.Prepared>(transparent.state.value).title)
        }

    @Test
    fun missingSaplingAddressStaysLoadingAndStillOffersBack() =
        runTest(dispatcher) {
            val router = FakeNavigationRouter()
            val vm = startedVm(account = keystoneAccount(), addressType = ReceiveAddressType.Sapling, router = router)

            assertIs<QrCodeState.Loading>(vm.state.value).onBack()
            assertEquals(1, router.backCount)
        }

    @Test
    fun backNavigatesBack() =
        runTest(dispatcher) {
            val router = FakeNavigationRouter()
            val vm = startedVm(router = router)

            assertIs<QrCodeState.Prepared>(vm.state.value).onBack()

            assertEquals(1, router.backCount)
        }

    private fun zashiAccount() =
        ZodlAccount(
            sdkAccount = mockk<Account>(relaxed = true),
            unifiedAddress = UNIFIED,
            transparentAddress = TRANSPARENT,
            saplingAddress = "sapling",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
        )

    private fun keystoneAccount() =
        KeystoneAccount(
            sdkAccount = mockk<Account>(relaxed = true),
            unifiedAddress = UNIFIED,
            transparentAddress = TRANSPARENT,
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
        )

    private fun viewOnlyAccount() =
        ViewOnlyAccount(
            sdkAccount = mockk<Account>(relaxed = true),
            unifiedAddress = UNIFIED,
            transparentAddress = TRANSPARENT,
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
            name = stringRes("Savings"),
            customName = "Savings",
            icon = 0,
        )

    private fun TestScope.startedVm(
        account: WalletAccount = zashiAccount(),
        addressType: ReceiveAddressType = ReceiveAddressType.Unified,
        copyToClipboard: CopyToClipboardUseCase = mockk(relaxed = true),
        shareQR: ShareQRUseCase = mockk(relaxed = true),
        router: FakeNavigationRouter = FakeNavigationRouter(),
        viewOnlyContent: ViewOnlyContent = mockk(relaxed = true),
        access: AppAccessSession = AppAccessSession().apply { unlock() },
        accountFlow: Flow<WalletAccount?> = flowOf(account),
        ownership: WalletWriteAccess = WalletWriteAccess(),
    ): QrCodeVM {
        val observeSelectedWalletAccount =
            mockk<ObserveSelectedWalletAccountUseCase> {
                every { this@mockk() } returns accountFlow
            }
        val vm =
            QrCodeVM(
                observeSelectedWalletAccount = observeSelectedWalletAccount,
                addressTypeOrdinal = addressType.ordinal,
                copyToClipboard = copyToClipboard,
                navigationRouter = router,
                shareQR = shareQR,
                viewOnlyContent = viewOnlyContent,
                appAccessSession = access,
                walletWriteAccess = ownership,
            )
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()
        return vm
    }
}

private const val UNIFIED = "u1unifiedaddress"

private const val TRANSPARENT = "t1transparentaddress"

/**
 * Resource-backed texts: mockk cannot return the inline `StringResource.ByString` from a mocked
 * interface property.
 */
private val VIEW_ONLY_SHIELDED = StringResource.ByResource(resource = 11, args = emptyList())

private val VIEW_ONLY_TRANSPARENT = StringResource.ByResource(resource = 12, args = emptyList())

private class FakeNavigationRouter : NavigationRouter {
    var backCount = 0
        private set
    val forwardedRoutes = mutableListOf<Any>()
    val replacedRoutes = mutableListOf<Any>()

    override fun forward(vararg routes: Any) {
        forwardedRoutes.addAll(routes)
    }

    override fun replace(vararg routes: Any) {
        replacedRoutes.addAll(routes)
    }

    override fun replaceAll(vararg routes: Any) = Unit

    override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

    override fun back() {
        backCount++
    }

    override fun backTo(route: KClass<*>) = Unit

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

    override fun backToRoot() = Unit

    override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
}
