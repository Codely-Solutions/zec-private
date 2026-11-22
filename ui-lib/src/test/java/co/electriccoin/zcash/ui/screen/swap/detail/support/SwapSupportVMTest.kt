package co.electriccoin.zcash.ui.screen.swap.detail.support

import android.content.Intent
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.provider.BlockchainProviderImpl
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSwapStatusUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.SwapQuoteStatusData
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.support.model.SupportInfo
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwapSupportVMTest {
    private val saved = TransactionSwapMetadata(
        depositAddress = "synthetic-saved-deposit-reference",
        provider = "near",
        totalFees = Zatoshi(0),
        totalFeesUsd = BigDecimal.ZERO,
        lastUpdated = Instant.EPOCH,
        origin = SwapAssetTestFixture.simpleAsset("USDC", "arb"),
        destination = SwapAssetTestFixture.zecSimpleAsset(),
        mode = SwapMode.FLEX_INPUT,
        recordedStatus = SwapStatus.FAILED,
        amountOutFormatted = BigDecimal.ONE,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun reportIssueSharesTheLatestDisplayedSwapBeforeReturning() = runTest {
        val latest = mockk<SwapQuoteStatus> {
            every { depositAddress } returns DynamicSwapAddress(saved.depositAddress)
            every { originAsset } returns SwapAssetTestFixture.asset("BTC", "btc")
            every { destinationAsset } returns SwapAssetTestFixture.asset("ZEC", "zec")
        }
        assertReportHandoff(latest, "BTC - Bitcoin", "ZEC - Zcash")
    }

    @Test
    fun reportIssueSharesTheSavedReferenceWhenStatusIsUnavailable() = runTest {
        assertReportHandoff(null, "USDC - Arbitrum", "zec - Zcash")
    }

    @Test
    fun reportIssueWaitsForSavedOrLiveSwapDetails() = runTest {
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observe(saved.depositAddress) } returns flowOf(SwapQuoteStatusData())
        }
        val navigation = mockk<NavigationRouter>()
        val share = mockk<SendEmailUseCase>()
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress),
            GetReloadableSwapQuoteUseCase(getStatus),
            navigation,
            share,
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            val report = assertNotNull(vm.state.value).reportIssueButton
            assertFalse(report.isEnabled)
            report.onClick()
            runCurrent()
            verify { listOf(navigation, share) wasNot Called }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun TestScope.assertReportHandoff(status: SwapQuoteStatus?, origin: String, destination: String) {
        val application = RuntimeEnvironment.getApplication()
        val supportInfo = mockk<SupportInfo> {
            every { toSupportString(any()) } returns "Synthetic device diagnostics"
        }
        val getSupport = mockk<GetSupportUseCase> { coEvery { this@mockk() } returns supportInfo }
        val share = SendEmailUseCase(application, getSupport, BlockchainProviderImpl(application))
        val statusFlow = MutableStateFlow(SwapQuoteStatusData())
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observe(saved.depositAddress) } returns statusFlow
        }
        val navigation = mockk<NavigationRouter>()
        var launchedOnBack: Intent? = null
        every { navigation.back() } answers {
            launchedOnBack = shadowOf(application).nextStartedActivity
        }
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress),
            GetReloadableSwapQuoteUseCase(getStatus),
            navigation,
            share,
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            statusFlow.value = SwapQuoteStatusData(status = status, isLoading = false, savedMetadata = saved)
            runCurrent()
            val report = assertNotNull(vm.state.value).reportIssueButton
            assertEquals(stringRes(R.string.reportSwap_report), report.text)
            assertTrue(report.isEnabled)
            assertNull(shadowOf(application).nextStartedActivity)
            report.onClick()
            runCurrent()

            val chooser = assertNotNull(launchedOnBack)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val shared = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, shared.action)
            assertEquals("text/plain", shared.type)
            assertNull(shared.data)
            assertNull(shared.component)
            assertFalse(shared.hasExtra(Intent.EXTRA_EMAIL))
            assertEquals(
                application.getString(R.string.transaction_detail_support_email_subject),
                shared.getStringExtra(Intent.EXTRA_SUBJECT),
            )
            val body = assertNotNull(shared.getStringExtra(Intent.EXTRA_TEXT))
            assertContains(
                body,
                application.getString(
                    R.string.transaction_detail_support_email_body,
                    saved.depositAddress,
                    origin,
                    destination,
                ),
            )
            assertContains(body, "Synthetic device diagnostics")
            verify(exactly = 1) { getStatus.observe(saved.depositAddress) }
            verify(exactly = 1) { navigation.back() }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }
}
