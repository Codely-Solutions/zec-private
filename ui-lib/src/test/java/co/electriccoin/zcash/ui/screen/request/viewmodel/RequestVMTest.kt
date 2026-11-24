package co.electriccoin.zcash.ui.screen.request.viewmodel

import android.app.Application
import androidx.compose.ui.graphics.Color
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.AccountIconFixtures
import co.electriccoin.zcash.ui.common.usecase.AccountIconFixtures.PICKED
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321BuildUriUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.model.OnAmount
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The memo stage of a ZEC request shows the account's icon: a view-only account's in its colour, a
 * hardware wallet's own drawable, and the ZEC icon for the Zodl account even once it has a colour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestVMTest {
    private val allMetadata = MutableStateFlow<Map<AccountUuid, MetadataV3>>(emptyMap())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aViewOnlyAccountsMemoStageShowsItsColoredIcon() =
        runTest {
            allMetadata.value = mapOf(EditAccountFixtures.viewOnlyUuid to AccountIconFixtures.metadata(Color(PICKED)))

            assertEquals(AccountIconFixtures.viewOnlyIcon(Color(PICKED)), memoIcon(EditAccountFixtures.viewOnly()))
        }

    @Test
    fun aViewOnlyAccountWithoutAColorShowsTheDefaultCircle() =
        runTest {
            assertEquals(AccountIconFixtures.viewOnlyIcon(), memoIcon(EditAccountFixtures.viewOnly()))
        }

    @Test
    fun hardwareWalletsShowTheirOwnDrawable() =
        runTest {
            allMetadata.value = mapOf(EditAccountFixtures.hardwareUuid to AccountIconFixtures.metadata(Color(PICKED)))

            val hardwareWallets =
                listOf(EditAccountFixtures.keystone(isSelected = true), EditAccountFixtures.ledger(isSelected = true))

            hardwareWallets.forEach { account ->
                assertEquals(imageRes(account.icon), memoIcon(account))
            }
        }

    @Test
    fun theZodlAccountKeepsTheZecIconEvenWithAColor() =
        runTest {
            allMetadata.value = mapOf(EditAccountFixtures.zodlUuid to AccountIconFixtures.metadata(Color(PICKED)))

            assertEquals(imageRes(R.drawable.ic_zec_round_full), memoIcon(EditAccountFixtures.zodl()))
        }

    @Test
    fun aFinalizedQrKeepsItsOriginalAddressAcrossRotationAndRetiresOnOwnerChange() = runTest {
        val original = EditAccountFixtures.zodl().copy(unifiedAddress = "original-address")
        val rotated = original.copy(unifiedAddress = "rotated-address")
        val accounts = MutableStateFlow<WalletAccount?>(original)
        val share = mockk<ShareQRUseCase> {
            coEvery { this@mockk(any(), any(), any(), any(), any(), any()) } coAnswers {
                assertEquals("zcash:original-address", firstArg())
                assertTrue(arg<() -> Boolean>(5)())
                true
            }
        }
        val vm = startedRequest(original, share, accounts = accounts, encode = { "zcash:$it" })
        assertIs<RequestState.Memo>(vm.state.value).onDone()
        val old = assertIs<RequestState.QrCode>(vm.state.value)
        accounts.value = rotated
        val ready = assertIs<RequestState.QrCode>(vm.state.value)
        assertEquals(original.unifiedAddress, ready.walletAddress.address)
        ready.onQrCodeShare(mockk(), 1, ready.request.qrCodeState.requestUri)
        coVerify(exactly = 1) { share(any(), any(), any(), any(), any(), any()) }

        accounts.value = EditAccountFixtures.keystone(isSelected = true)
        assertIs<RequestState.Amount>(vm.state.value)
        old.onQrCodeShare(mockk(), 1, old.request.qrCodeState.requestUri)
        accounts.value = original
        assertIs<RequestState.Amount>(vm.state.value)
        coVerify(exactly = 1) { share(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun requestSharesOnlyTheDisplayedUriAndRefusesARevokedGrant() = runTest {
        val access = AppAccessSession().apply { unlock() }
        val share = mockk<ShareQRUseCase> {
            coEvery { this@mockk(any(), any(), any(), any(), any(), any()) } coAnswers {
                val permitted = arg<() -> Boolean>(5)
                assertEquals("zcash:fixture", firstArg())
                assertTrue(permitted())
                access.lock()
                access.unlock()
                assertFalse(permitted())
                false
            }
        }
        val vm = startedRequest(EditAccountFixtures.zodl(), share, access)
        assertIs<RequestState.Memo>(vm.state.value).onDone()
        val ready = assertIs<RequestState.QrCode>(vm.state.value)
        ready.onQrCodeShare(mockk(), 1, "zcash:other")
        ready.onQrCodeShare(mockk(), 1, ready.request.qrCodeState.requestUri)
        coVerify(exactly = 1) { share(any(), any(), any(), any(), any(), any()) }
    }

    /** The icon of [account]'s memo stage, reached from the amount stage of a shielded request. */
    private fun TestScope.memoIcon(account: WalletAccount): ImageResource =
        assertIs<RequestState.Memo>(startedRequest(account).state.value).icon

    private fun TestScope.startedRequest(
        account: WalletAccount,
        share: ShareQRUseCase = mockk(relaxed = true),
        access: AppAccessSession = AppAccessSession().apply { unlock() },
        accounts: Flow<WalletAccount?> = flowOf(account),
        encode: (String) -> String = { "zcash:fixture" },
    ): RequestVM {
        val vm =
            RequestVM(
                addressTypeOrdinal = ReceiveAddressType.Unified.ordinal,
                application = mockk<Application> { every { getString(any()) } returns "0" },
                exchangeRateRepository =
                    mockk<ExchangeRateRepository> {
                        every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
                    },
                getZcashCurrency = mockk(relaxed = true),
                zip321BuildUriUseCase = mockk<Zip321BuildUriUseCase> {
                    every { this@mockk(any<String>(), any<BigDecimal>(), any<String>()) } answers { encode(firstArg()) }
                },
                observeSelectedWalletAccount =
                    mockk<ObserveSelectedWalletAccountUseCase> {
                        every { this@mockk() } returns accounts
                    },
                navigationRouter = mockk(relaxed = true),
                shareQR = share,
                observeAccountIcon = AccountIconFixtures.useCase(allMetadata),
                appAccessSession = access,
                walletWriteAccess = WalletWriteAccess(),
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }

        assertIs<RequestState.Amount>(vm.state.value).onAmount(OnAmount.Number(1))
        assertIs<RequestState.Amount>(vm.state.value).onDone()

        return vm
    }
}
