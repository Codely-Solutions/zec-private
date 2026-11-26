package co.electriccoin.zcash.ui.screen.swap.detail.support

import android.content.Intent
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.screen.swap.mismatch.SwapQuoteMismatchArgs
import co.electriccoin.zcash.ui.screen.swap.mismatch.SwapQuoteMismatchVM
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.near.NearSignedQuoteEvidence
import co.electriccoin.zcash.ui.common.model.near.syntheticSignedQuote
import co.electriccoin.zcash.ui.common.provider.BlockchainProviderImpl
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
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
        val owner = reportOwner(0)
        val accounts = accountSource(MutableStateFlow(owner))
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observeForAccount(saved.depositAddress, owner) } returns flowOf(SwapQuoteStatusData())
        }
        val navigation = reportNavigation()
        val share = mockk<SendEmailUseCase>()
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress, ownerKey),
            GetReloadableSwapQuoteUseCase(getStatus, accountDataSource = accounts),
            navigation,
            share,
            mockk(),
            AppAccessSession().apply { unlock() },
            accounts,
            WalletWriteAccess(),
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            val report = assertNotNull(vm.state.value).reportIssueButton
            assertFalse(report.isEnabled)
            report.onClick()
            runCurrent()
            verify(exactly = 0) { navigation.back() }
            verify { share wasNot Called }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun sharesOnlyTheExplicitlyRequestedQuoteFromTheCurrentWalletGeneration() = runTest {
        withQuoteSupport {
            coEvery { metadata.getSwapMetadata(tracked.depositAddress, tracked.ownerAccount) } returns
                tracked.copy(walletWriteToken = 3L)
            val shareQuote = assertNotNull(vm.state.value?.shareQuoteButton)
            assertEquals(stringRes(R.string.reportSwap_shareQuote), shareQuote.text)
            assertNull(nextChooser())
            shareQuote.onClick()
            runCurrent()
            assertNull(nextChooser())

            coEvery { metadata.getSwapMetadata(tracked.depositAddress, tracked.ownerAccount) } returns tracked
            shareQuote.onClick()
            runCurrent()
            val chooser = assertNotNull(nextChooser())
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val shared = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(syntheticSignedQuote, shared.getStringExtra(Intent.EXTRA_TEXT))
            assertFalse(shared.hasExtra(Intent.EXTRA_EMAIL))
        }
    }

    @Test
    fun relockRevokesTheDisplayedShareCallbackEvenAfterUnlock() = runTest {
        withQuoteSupport {
            val staleAction = assertNotNull(vm.state.value?.shareQuoteButton).onClick
            session.lock()
            staleAction()
            runCurrent()
            assertNull(vm.state.value?.shareQuoteButton)
            assertNull(nextChooser())

            session.unlock()
            runCurrent()
            staleAction()
            runCurrent()
            assertNull(nextChooser())
            coVerify(exactly = 0) { metadata.reloadSwapMetadata(any()) }

            assertNotNull(vm.state.value?.shareQuoteButton).onClick()
            runCurrent()
            assertEquals(Intent.ACTION_CHOOSER, assertNotNull(nextChooser()).action)
        }
    }

    @Test
    fun relockDuringEitherMetadataReadDiscardsThePendingShare() = runTest {
        for (pauseDuringReload in listOf(true, false)) {
            withQuoteSupport {
                val entered = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                suspend fun pausedRead(): TransactionSwapMetadata {
                    entered.complete(Unit)
                    resume.await()
                    return tracked
                }
                if (pauseDuringReload) {
                    coEvery { metadata.reloadSwapMetadata(tracked) } coAnswers { pausedRead() }
                } else {
                    coEvery { metadata.getSwapMetadata(tracked.depositAddress, tracked.ownerAccount) } coAnswers {
                        pausedRead()
                    }
                }

                assertNotNull(vm.state.value?.shareQuoteButton).onClick()
                runCurrent()
                assertTrue(entered.isCompleted)
                session.lock()
                runCurrent()
                assertNull(nextChooser())
                session.unlock()
                resume.complete(Unit)
                runCurrent()
                assertNull(nextChooser())
            }
        }
    }

    @Test
    fun pendingGenericReportRequiresTheOriginalGrantAccountAndWallet() = runTest {
        for (revoke in listOf("relock", "account", "wallet", "entry", "background")) assertRevokedReport(revoke)
    }

    private suspend fun TestScope.assertRevokedReport(revoke: String) {
        val application = RuntimeEnvironment.getApplication()
        val diagnosticsEntered = CompletableDeferred<Unit>()
        val releaseDiagnostics = CompletableDeferred<Unit>()
        val supportInfo = mockk<SupportInfo> {
            every { toSupportString(any()) } returns "Synthetic device diagnostics"
        }
        val getSupport = mockk<GetSupportUseCase> {
            coEvery { this@mockk() } coAnswers {
                diagnosticsEntered.complete(Unit)
                releaseDiagnostics.await()
                supportInfo
            }
        }
        val owner = reportOwner(0)
        val selection = MutableStateFlow<ZodlAccount?>(owner)
        val accounts = accountSource(selection)
        val writeAccess = WalletWriteAccess()
        val tracked = saved.copy(ownerAccount = owner, walletWriteToken = writeAccess.token())
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observeForAccount(saved.depositAddress, owner) } returns
                flowOf(SwapQuoteStatusData(savedMetadata = tracked))
        }
        val entry = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("support", null))
        val navigation = reportNavigation(entry)
        val session = AppAccessSession().apply { unlock() }
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress, ownerKey),
            GetReloadableSwapQuoteUseCase(getStatus, accountDataSource = accounts),
            navigation,
            SendEmailUseCase(application, getSupport, BlockchainProviderImpl(application)),
            mockk(),
            session,
            accounts,
            writeAccess,
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            assertNotNull(vm.state.value?.reportIssueButton).onClick()
            runCurrent()
            assertTrue(diagnosticsEntered.isCompleted)
            when (revoke) {
                "relock" -> {
                    session.lock()
                    session.unlock()
                }
                "account" -> selection.value = reportOwner(1)
                "wallet" -> writeAccess.beginReset()
                "entry" -> entry.value = ActiveNavigationEntry("another-screen", null)
                "background" -> entry.value = entry.value?.copy(hostResumed = false)
            }
            runCurrent()
            releaseDiagnostics.complete(Unit)
            runCurrent()

            assertNull(shadowOf(application).nextStartedActivity)
            verify(exactly = 0) { navigation.back() }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun mismatchReportUsesTheSameFinalChooserGuard() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val resume = CompletableDeferred<Unit>()
        val diagnostics = mockk<GetSupportUseCase> {
            coEvery { this@mockk() } coAnswers {
                resume.await()
                mockk<SupportInfo> { every { toSupportString(any()) } returns "Synthetic diagnostics" }
            }
        }
        val session = AppAccessSession().apply { unlock() }
        val navigation = reportNavigation()
        val vm = SwapQuoteMismatchVM(
            SwapQuoteMismatchArgs(
                provider = "near", mode = SwapMode.FLEX_INPUT,
                originTokenTicker = "btc", originChainTicker = "btc",
                destinationTokenTicker = "zec", destinationChainTicker = "zec",
                mismatchType = SwapQuoteMismatchType.OUTPUT_AMOUNT, depositAddress = saved.depositAddress,
            ),
            navigation,
            SendEmailUseCase(application, diagnostics, BlockchainProviderImpl(application)),
            session,
            WalletWriteAccess(),
        )
        try {
            runCurrent()
            requireNotNull(vm.state.value).reportButton.onClick()
            runCurrent()
            session.lock()
            session.unlock()
            resume.complete(Unit)
            runCurrent()
            assertNull(shadowOf(application).nextStartedActivity)
            verify(exactly = 0) { navigation.back() }

            requireNotNull(vm.state.value).reportButton.onClick()
            runCurrent()
            assertEquals(Intent.ACTION_CHOOSER, shadowOf(application).nextStartedActivity?.action)
            verify(exactly = 1) { navigation.back() }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun pendingResetRevokesOriginalQuoteShareWhileTheFinalReadIsSuspended() = runTest {
        withQuoteSupport {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { metadata.getSwapMetadata(tracked.depositAddress, tracked.ownerAccount) } coAnswers {
                entered.complete(Unit)
                release.await()
                tracked
            }
            val shareQuote = assertNotNull(vm.state.value?.shareQuoteButton)
            assertTrue(shareQuote.isEnabled)
            shareQuote.onClick()
            runCurrent()
            assertTrue(entered.isCompleted)
            pendingReset.value = true
            release.complete(Unit)
            runCurrent()
            assertNull(nextChooser())
        }
    }

    private fun TestScope.withQuoteSupport(test: QuoteSupportFixture.() -> Unit) {
        val application = RuntimeEnvironment.getApplication()
        val share = SendEmailUseCase(application, mockk(), BlockchainProviderImpl(application))
        val owner = mockk<ZodlAccount> {
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
        }
        val tracked = saved.copy(
            ownerAccount = owner,
            walletWriteToken = 0L,
            signedQuoteEvidence = NearSignedQuoteEvidence(syntheticSignedQuote),
        )
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observeForAccount(saved.depositAddress, owner) } returns
                flowOf(SwapQuoteStatusData(savedMetadata = tracked))
        }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(saved.depositAddress, owner) } returns tracked
            coEvery { reloadSwapMetadata(tracked) } returns tracked
        }
        val session = AppAccessSession().apply { unlock() }
        val accounts = accountSource(MutableStateFlow(owner))
        val pendingReset = MutableStateFlow(false)
        val progress = mockk<co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore> {
            every { isPending } returns pendingReset
        }
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress, ownerKey),
            GetReloadableSwapQuoteUseCase(getStatus, accountDataSource = accounts),
            reportNavigation(),
            share,
            metadata,
            session,
            accounts,
            WalletWriteAccess(progress),
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            QuoteSupportFixture(vm, metadata, tracked, session, pendingReset) {
                shadowOf(application).nextStartedActivity
            }.test()
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun reportNavigation(
        entry: MutableStateFlow<ActiveNavigationEntry?> = MutableStateFlow(ActiveNavigationEntry("support", null)),
    ) = mockk<NavigationRouter>(relaxed = true) {
        every { activeEntry } returns entry
    }

    private val ownerKey = "00".repeat(16)

    private fun reportOwner(id: Byte) = mockk<ZodlAccount> {
        every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16) { id }))
    }

    private fun accountSource(selection: MutableStateFlow<out ZodlAccount?>) = mockk<AccountDataSource> {
        every { selectedAccount } returns selection
    }

    private class QuoteSupportFixture(
        val vm: SwapSupportVM,
        val metadata: MetadataRepository,
        val tracked: TransactionSwapMetadata,
        val session: AppAccessSession,
        val pendingReset: MutableStateFlow<Boolean>,
        val nextChooser: () -> Intent?,
    )

    private fun TestScope.assertReportHandoff(status: SwapQuoteStatus?, origin: String, destination: String) {
        val application = RuntimeEnvironment.getApplication()
        val supportInfo = mockk<SupportInfo> {
            every { toSupportString(any()) } returns "Synthetic device diagnostics"
        }
        val getSupport = mockk<GetSupportUseCase> { coEvery { this@mockk() } returns supportInfo }
        val share = SendEmailUseCase(application, getSupport, BlockchainProviderImpl(application))
        val owner = reportOwner(0)
        val accounts = accountSource(MutableStateFlow(owner))
        val statusFlow = MutableStateFlow(SwapQuoteStatusData())
        val getStatus = mockk<GetSwapStatusUseCase> {
            every { observeForAccount(saved.depositAddress, owner) } returns statusFlow
        }
        val navigation = reportNavigation()
        var launchedOnBack: Intent? = null
        every { navigation.back() } answers {
            launchedOnBack = shadowOf(application).nextStartedActivity
        }
        val vm = SwapSupportVM(
            SwapSupportArgs(saved.depositAddress, ownerKey),
            GetReloadableSwapQuoteUseCase(getStatus, accountDataSource = accounts),
            navigation,
            share,
            mockk(),
            AppAccessSession().apply { unlock() },
            accounts,
            WalletWriteAccess(),
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }
        try {
            runCurrent()
            statusFlow.value = SwapQuoteStatusData(
                status = status,
                isLoading = false,
                savedMetadata = saved.copy(
                    ownerAccount = owner,
                    walletWriteToken = 0L,
                    signedQuoteEvidence = NearSignedQuoteEvidence(syntheticSignedQuote),
                ),
            )
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
            assertFalse(body.contains("synthetic-recipient"))
            verify(exactly = 1) { getStatus.observeForAccount(saved.depositAddress, owner) }
            verify(exactly = 1) { navigation.back() }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
        }
    }
}
