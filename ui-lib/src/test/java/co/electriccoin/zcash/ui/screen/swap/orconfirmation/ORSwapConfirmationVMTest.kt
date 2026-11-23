package co.electriccoin.zcash.ui.screen.swap.orconfirmation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.SaveORSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareImageUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.FileShareUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.io.OutputStream
import java.math.BigDecimal
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * The swap deposit confirmation screen: sharing the deposit QR hands the share use case the deposit address
 * together with the composed "send X TOKEN on CHAIN" text, and the copy actions put the address and the amount
 * on the clipboard. The fiat symbol comes from `android.icu`, which the JVM android stubs cannot answer, so
 * [FiatCurrency.USD] is stubbed for the state to build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ORSwapConfirmationVMTest {
    @get:Rule val directory = TemporaryFolder()

    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(FiatCurrency.USD)
        Dispatchers.resetMain()
    }

    @Test
    fun shareSendsTheDepositQrWithTheComposedShareText() =
        runTest(dispatcher) {
            val shareQR = mockk<ShareQRUseCase>(relaxed = true)
            val vm = startedVm(shareQR = shareQR)

            requireNotNull(vm.state.value).shareButton.onClick()
            runCurrent()

            coVerify(exactly = 1) {
                shareQR(
                    qrData = DEPOSIT_ADDRESS,
                    shareText =
                        stringRes(
                            R.string.swap_to_zec_share_text,
                            stringResByNumber(AMOUNT_IN),
                            "BTC",
                            stringRes("BTC"),
                            CURRENCY_TICKER
                        ),
                    sharePickerText = stringRes("Swap Deposit Address"),
                    filenamePrefix = "swap_deposit_address_",
                    centerIcon = null,
                    isAllowed = any()
                )
            }
        }

    @Test
    fun copyActionsPutTheAddressAndTheAmountOnTheClipboard() =
        runTest(dispatcher) {
            val copyToClipboard = mockk<CopyToClipboardUseCase>(relaxed = true)
            val vm = startedVm(copyToClipboard = copyToClipboard)
            val state = requireNotNull(vm.state.value)

            state.copyButton.onClick()
            state.onAmountClick()

            verify(exactly = 1) { copyToClipboard(DEPOSIT_ADDRESS) }
            verify(exactly = 1) { copyToClipboard(AMOUNT_IN.toPlainString()) }
        }

    @Test
    fun trackingIsSavedBeforeFundingInstructionsAppear() = runTest(dispatcher) {
        val save = mockk<SaveORSwapUseCase>(relaxed = true)
        val vm = startedVm(saveORSwap = save)
        requireNotNull(vm.state.value)
        coVerify(exactly = 1) { save.persistBeforeFunding(any()) }
    }

    @Test
    fun expiredFundingKeepsTrackingActionAndBlocksDepositSharing() = runTest(dispatcher) {
        val copy = mockk<CopyToClipboardUseCase>(relaxed = true)
        val share = mockk<ShareQRUseCase>(relaxed = true)
        val vm = startedVm(copyToClipboard = copy, shareQR = share, deadline = Instant.parse("2000-01-01T00:00:00Z"))
        val state = requireNotNull(vm.state.value)
        assertFalse(state.fundingEnabled)
        requireNotNull(state.freshQuoteButton)
        requireNotNull(state.expiryMessage)
        state.copyButton.onClick()
        state.shareButton.onClick()
        state.onAmountClick()
        runCurrent()
        verify(exactly = 0) { copy(any<String>()) }
        coVerify(exactly = 0) { share(any(), any(), any(), any(), any()) }
    }

    @Test
    fun aStagedDepositQrRequiresTheSameUnexpiredQuote() = runTest(dispatcher) {
        StageChange.entries.forEach { assertStagedShare(it) }
    }

    private suspend fun TestScope.assertStagedShare(change: StageChange) {
        val quotes = MutableStateFlow<SwapQuoteData?>(null)
        val context = mockk<Context> {
            every { cacheDir } returns directory.root
            every { startActivity(any()) } answers { }
        }
        val intent = mockk<Intent>()
        val versionInfo = mockk<GetVersionInfoProvider> { every { this@mockk() } returns mockk() }
        val shareImage = ShareImageUseCase(context, versionInfo, WalletWriteAccess())
        val deadline = Instant.parse("2100-01-01T00:00:00Z")
        val clock = AtomicReference(deadline - 1.milliseconds)
        val shared = CompletableDeferred<Boolean>()
        val bitmap = mockk<Bitmap> {
            every { compress(any(), any(), any()) } answers {
                thirdArg<OutputStream>().write(1)
                when (change) {
                    StageChange.CLEAR -> quotes.value = null
                    StageChange.REPLACE -> quotes.value = SwapQuoteData.Success(depositQuote(deadline))
                    StageChange.EXPIRE -> clock.set(deadline)
                    StageChange.NONE -> Unit
                }
                true
            }
        }
        val share = mockk<ShareQRUseCase> {
            coEvery { this@mockk(any(), any(), any(), any(), any(), any()) } coAnswers {
                val isAllowed = arg<() -> Boolean>(5)
                val launched = shareImage(
                    bitmap,
                    sharePickerText = "Synthetic deposit QR",
                    filePrefix = "swap_deposit_address_",
                    fileSuffix = ".png",
                    isAllowed = isAllowed,
                )
                shared.complete(launched)
                launched
            }
        }
        mockkObject(FileShareUtil)
        try {
            every {
                FileShareUtil.newShareContentIntent(context, any<File>(), any(), any(), any(), any())
            } returns intent
            val vm = startedVm(shareQR = share, quotes = quotes, deadline = deadline) { clock.get() }
            requireNotNull(vm.state.value).shareButton.onClick()

            val allowed = change == StageChange.NONE
            assertEquals(allowed, shared.await())
            verify(exactly = if (allowed) 1 else 0) { context.startActivity(intent) }
            val files = directory.root.walkTopDown().filter { it.isFile }.toList()
            if (!allowed) assertTrue(files.isEmpty())
            files.forEach { assertTrue(it.delete()) }
            if (change == StageChange.CLEAR) {
                runCurrent()
                assertNull(vm.state.value)
            }
            io.mockk.clearMocks(context, answers = false)
        } finally {
            unmockkObject(FileShareUtil)
        }
    }

    private fun TestScope.startedVm(
        copyToClipboard: CopyToClipboardUseCase = mockk(relaxed = true),
        shareQR: ShareQRUseCase = mockk(relaxed = true),
        router: FakeNavigationRouter = FakeNavigationRouter(),
        saveORSwap: SaveORSwapUseCase = mockk(relaxed = true),
        deadline: Instant = Instant.parse("2100-01-01T00:00:00Z"),
        quotes: MutableStateFlow<SwapQuoteData?> = MutableStateFlow(null),
        clock: Clock = Clock.System,
    ): ORSwapConfirmationVM {
        quotes.value = SwapQuoteData.Success(depositQuote(deadline))
        val swapRepository =
            mockk<SwapRepository> {
                every { this@mockk.quote } returns quotes
            }
        val vm =
            ORSwapConfirmationVM(
                swapRepository = swapRepository,
                cancelSwapQuote = mockk<CancelSwapQuoteUseCase>(relaxed = true),
                copyToClipboard = copyToClipboard,
                navigationRouter = router,
                saveORSwap = saveORSwap,
                shareQR = shareQR,
            ).also { it.clock = clock }
        backgroundScope.launch { vm.state.collect { } }
        runCurrent()
        return vm
    }

    private enum class StageChange { CLEAR, REPLACE, EXPIRE, NONE }

    private fun depositQuote(deadline: Instant) =
            FakeSwapQuote(
                originAsset = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc"),
                destinationAsset = SwapAssetTestFixture.zecAsset(),
                mode = SwapMode.EXACT_INPUT,
                amountIn = AMOUNT_IN,
                amountInFormatted = AMOUNT_IN,
                amountOutFormatted = BigDecimal.ONE,
                depositAddress = DEPOSIT_ADDRESS,
                destinationAddress = "u1destination",
                refundAddress = "bc1refund",
                deadline = deadline,
            )
}

private const val DEPOSIT_ADDRESS = "bc1depositaddress"

private val AMOUNT_IN = BigDecimal("0.5")

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
