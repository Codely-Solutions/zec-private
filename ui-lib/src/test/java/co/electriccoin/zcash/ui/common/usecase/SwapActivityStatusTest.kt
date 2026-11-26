package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataReadUnavailableException
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.mapper.ActivityMapper
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.SelectedAccountSelection
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionMetadata
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailArgs
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SwapActivityStatusTest {
    private val id = TransactionId.new(ByteArray(32))
    private val deposit = "fixture-deposit"
    private val owner = EditAccountFixtures.zodl()
    private val overview = mockk<TransactionOverview> {
        every { zip318Kind } returns Zip318Kind.NOT_CLASSIFIED
    }
    private val failed = SendTransaction.Failed(
        id, Zatoshi(100_000), null, 0, Zatoshi(10_000), emptyList(), overview, deposit,
        accountUuid = owner.sdkAccount.accountUuid,
    )
    private val confirmed = SendTransaction.Success(
        id, failed.amount, Instant.EPOCH, 0, failed.fee, emptyList(), overview, deposit,
        accountUuid = owner.sdkAccount.accountUuid,
    )

    @Test
    fun activityShowsSwappedWhenDetailsConfirmSuccessBeforeWalletSyncCatchesUp() = runTest {
        val storedSwap = swap(SwapStatus.PENDING)
        val metadata = MutableStateFlow(TransactionMetadata(false, true, null, storedSwap))
        val origin = SwapAssetTestFixture.zecAsset()
        val destination = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")
        val remote = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.SUCCESS
            every { mode } returns SwapMode.EXACT_INPUT
            every { amountOutFormatted } returns BigDecimal("0.0001")
            every { originAsset } returns origin
            every { destinationAsset } returns destination
        }
        val metadataRepository = mockk<MetadataRepository>(relaxed = true) {
            coEvery { getSwapMetadata(deposit, owner) } returns storedSwap
            every { observeTransactionMetadata(failed) } returns metadata
            coEvery { updateSwap(storedSwap, any()) } answers {
                val status = secondArg<SwapQuoteStatus>()
                val saved = storedSwap.copy(recordedStatus = status.status)
                metadata.value = metadata.value.copy(swapMetadata = saved)
                saved
            }
        }
        val transactions = mockk<TransactionRepository> {
            every { observeTransaction(id.txIdString()) } returns flowOf(failed)
            coEvery { getMemos(failed) } returns emptyList()
            coEvery { resolveWalletAddress(deposit) } returns null
        }
        val contacts = mockk<AddressBookRepository> {
            every { observeContactByAddress(deposit) } returns flowOf(null)
        }
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(storedSwap, any()) } returns remote
        }
        assertFailed(activity(failed, metadata.value.swapMetadata), stringRes(R.string.swapStatus_swapFailed))
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns flowOf(owner) }
        val details = assertNotNull(GetTransactionDetailByIdUseCase(
            transactions, contacts, metadataRepository, GetSwapStatusUseCase(metadataRepository, swaps), accounts,
        ).observe(id.txIdString()).first { it?.swap?.status != null })

        assertEquals(SwapStatus.SUCCESS, details.swap?.status?.status)
        assertEquals(SwapStatus.SUCCESS, storedSwap.outgoingStatus(failed, details.swap?.status?.status))
        coVerify(exactly = 1) {
            metadataRepository.updateSwap(storedSwap, remote)
        }
        val settled = activity(details.transaction, metadata.value.swapMetadata)
        assertEquals(stringRes(R.string.swapStatus_swapped), settled.title)
        assertEquals(R.drawable.ic_transaction_sent, settled.bigIcon)
        assertEquals(StringResourceColor.PRIMARY, settled.amountColor())
        assertEquals(settled.title, activity(confirmed, metadata.value.swapMetadata).title)
    }

    @Test
    fun providerFailureRemainsVisibleAfterTheFundingTransactionConfirms() {
        val metadata = swap(SwapStatus.FAILED)
        assertFailed(activity(confirmed, metadata), stringRes(R.string.swapStatus_swapFailed))
        assertEquals(SwapStatus.FAILED, metadata.outgoingStatus(confirmed, SwapStatus.FAILED))
    }

    @Test
    fun providerRefundAndExpiryTakePrecedenceOverTheLocalFundingFailure() {
        for (status in listOf(SwapStatus.REFUNDED, SwapStatus.EXPIRED)) {
            val saved = swap(status)
            val expectedTitle = when (status) {
                SwapStatus.REFUNDED -> R.string.swapStatus_swapRefunded
                else -> R.string.swapAndPay_expiredTitle
            }
            assertEquals(stringRes(expectedTitle), activity(failed, saved).title)
            assertEquals(status, swap(SwapStatus.PENDING).outgoingStatus(failed, status))
        }
    }

    @Test
    fun attachedHistoryKeepsTheProvedNoSubmissionOutcome() {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
            val metadata = swap(SwapStatus.PENDING).copy(fundingNotSubmitted = true, mode = mode)
            assertEquals(stringRes(R.string.private_swap_funding_not_sent), activity(failed, metadata).title)
            val refundTitle = if (mode == SwapMode.EXACT_OUTPUT) {
                R.string.swapStatus_paymentRefunded
            } else {
                R.string.swapStatus_swapRefunded
            }
            val refunded = metadata.copy(recordedStatus = SwapStatus.REFUNDED)
            assertEquals(stringRes(refundTitle), activity(failed, refunded).title)
        }
    }

    @Test
    fun legacyMetadataWithoutARecordedStatusCannotOverrideALocalFailure() {
        assertFailed(activity(failed, swap(null)), stringRes(R.string.swapStatus_swapFailed))
        assertFailed(activity(failed, null), stringRes(R.string.transaction_history_sending_failed))
    }

    @Test
    fun crossPayUsesTheSameSettlementRule() {
        val metadata = swap(SwapStatus.SUCCESS).copy(mode = SwapMode.EXACT_OUTPUT)
        val paid = activity(failed, metadata)
        assertEquals(stringRes(R.string.swapStatus_paid), paid.title)
        assertEquals(R.drawable.ic_transaction_paid, paid.bigIcon)
        assertEquals(StringResourceColor.PRIMARY, paid.amountColor())
        val paymentFailed = activity(confirmed, metadata.copy(recordedStatus = SwapStatus.FAILED))
        assertEquals(stringRes(R.string.swapStatus_paymentFailed), paymentFailed.title)
        assertEquals(R.drawable.ic_transaction_pay_failed, paymentFailed.bigIcon)
        assertEquals(StringResourceColor.NEGATIVE, paymentFailed.amountColor())
    }

    @Test
    fun outgoingTrackingCanBeOpenedBeforeFundingAppearsInWalletHistory() = runTest {
        val saved = swap(SwapStatus.PENDING)
        val stored = TransactionMetadata(false, true, null, saved)
        val history = MutableStateFlow<List<Transaction>?>(null)
        val transactions = mockk<TransactionRepository> { every { this@mockk.transactions } returns history }
        val repository = mockk<MetadataRepository> {
            every { observeSwapMetadata() } returns flowOf(listOf(saved))
            every { observeTransactionMetadata(confirmed) } returns flowOf(stored)
        }
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns flowOf(owner) }
        val activity = GetActivitiesUseCase(transactions, repository, accounts).observe()
        val onlyTracked = assertIs<ActivityData.BySwap>(activity.first()?.single())
        var openedReference: String? = null
        val row = ActivityMapper().createTransactionState(
            onlyTracked, Instant.EPOCH, {}, { openedReference = it.depositAddress }, {},
        )
        assertEquals(stringRes(R.string.private_swap_funding_unconfirmed), row.title)
        row.onClick()
        assertEquals(deposit, openedReference)

        history.value = emptyList()
        assertEquals(onlyTracked, activity.first()?.single())
        history.value = listOf(confirmed)
        val withFunding = assertIs<ActivityData.ByTransaction>(activity.first()?.single())
        assertEquals(confirmed, withFunding.transaction)
    }

    @Test
    fun pendingProgressCanOpenSavedSwapBeforeTheTransactionAppears() {
        val router = mockk<NavigationRouter>(relaxed = true)
        val proposal = mockk<SwapTransactionProposal> {
            every { fundingAccount } returns owner
            every { quote } returns mockk {
                every { depositAddress } returns DynamicSwapAddress(deposit)
            }
        }

        ViewTransactionDetailAfterSuccessfulProposalUseCase(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), router,
        )(proposal, id.txIdString())

        verify(exactly = 1) {
            router.replaceAll(SwapDetailArgs(deposit, owner.sdkAccount.accountUuid.toStorageKeyId(), id.txIdString()))
        }
    }

    @Test
    fun progressUsesWalletFundingOnlyAfterTheMatchingOwnerAndDepositAppear() = runTest {
        val selection = MutableStateFlow<WalletAccount?>(owner)
        val history = MutableStateFlow<Transaction?>(null)
        val transactions = mockk<TransactionRepository> {
            every { observeTransaction(id.txIdString()) } returns history
        }
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selection }
        val useCase = GetTransactionDetailByIdUseCase(transactions, mockk(), mockk(), mockk(), accounts)
        val key = owner.sdkAccount.accountUuid.toStorageKeyId()
        val matches = useCase.observeSwapFunding(id.txIdString(), key, deposit)
        assertEquals(false, matches.first())
        history.value = confirmed
        assertEquals(true, matches.first())
        assertEquals(false, useCase.observeSwapFunding(id.txIdString(), key, "other-deposit").first())
        selection.value = EditAccountFixtures.keystone()
        assertEquals(false, matches.first())
    }

    @Test
    fun accountChangeHidesOldTransactionsAndRefusesOldDetailRoutes() = runTest {
        val nextOwner = EditAccountFixtures.keystone()
        val selection = MutableStateFlow<WalletAccount?>(owner)
        val history = MutableStateFlow<List<Transaction>?>(listOf(confirmed))
        val shown = MutableStateFlow(listOf(swap(SwapStatus.PENDING)))
        val transactions = mockk<TransactionRepository> {
            every { this@mockk.transactions } returns history
        }
        val metadata = mockk<MetadataRepository> {
            every { observeSwapMetadata() } returns shown
            every { observeTransactionMetadata(confirmed) } returns flowOf(TransactionMetadata(false, true, null, null))
        }
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selection }
        val activities = GetActivitiesUseCase(transactions, metadata, accounts).observe()
        assertEquals(2, activities.first()?.size)

        selection.value = nextOwner
        shown.value = listOf(swap(SwapStatus.PENDING).copy(ownerAccount = nextOwner))
        val current = assertIs<ActivityData.BySwap>(activities.first()?.single())
        assertEquals(nextOwner, current.swap.ownerAccount)
        assertEquals(listOf(confirmed), history.value)

        val firstOwnerKey = owner.sdkAccount.accountUuid.toStorageKeyId()
        val details = GetTransactionDetailByIdUseCase(transactions, mockk(), metadata, mockk(), accounts)
        assertNull(details.observe(id.txIdString(), firstOwnerKey).first())
        val swapStatus = mockk<GetSwapStatusUseCase>()
        val swaps = GetReloadableSwapQuoteUseCase(swapStatus, metadata, accounts)
        assertIs<ActivityDetailUnavailableException>(swaps.observe(deposit, firstOwnerKey).first().error)
        coVerify(exactly = 0) { swapStatus.observeForAccount(any(), any()) }
    }

    @Test
    fun loadedTransactionDetailClearsWhenItsTransactionDisappears() = runTest {
        val accountId = owner.sdkAccount.accountUuid.toStorageKeyId()
        val observed = MutableStateFlow<Transaction?>(confirmed)
        val transactions = mockk<TransactionRepository> {
            every { observeTransaction(id.txIdString()) } returns observed
            coEvery { getMemos(confirmed) } returns emptyList()
            coEvery { resolveWalletAddress(deposit) } returns null
        }
        val contacts = mockk<AddressBookRepository> { every { observeContactByAddress(deposit) } returns flowOf(null) }
        val metadata = mockk<MetadataRepository> {
            every { observeTransactionMetadata(confirmed) } returns flowOf(TransactionMetadata(false, true, null, null))
        }
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns flowOf(owner) }
        val details = GetTransactionDetailByIdUseCase(transactions, contacts, metadata, mockk(), accounts)

        // The production observer maps on Dispatchers.Default. Bound the real work in its dispatcher.
        val stages = withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                details.observe(id.txIdString(), accountId).onEach { if (it != null) observed.value = null }
                    .take(2).toList()
            }
        }
        assertEquals(confirmed, stages.first()?.transaction)
        assertNull(stages.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun activityPollingDeduplicatesRequestsAndDiscardsWorkFromThePreviousAccount() = runTest {
        val first = EditAccountFixtures.zodl()
        val second = EditAccountFixtures.keystone()
        val selection = MutableStateFlow<WalletAccount?>(first)
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selection }
        val pendingFirst = swap(SwapStatus.PENDING).copy(ownerAccount = first)
        val pendingSecond = pendingFirst.copy(ownerAccount = second)
        val result = mockk<SwapQuoteStatus>(relaxed = true) { every { status } returns SwapStatus.PENDING }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(deposit, first) } returns pendingFirst
            coEvery { getSwapMetadata(deposit, second) } returns pendingSecond
            coEvery { updateSwap(pendingSecond, result) } returns pendingSecond
        }
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(pendingFirst, any()) } coAnswers { awaitCancellation() }
            coEvery { checkSwapStatus(pendingSecond, any()) } returns result
        }
        val poll = UpdateSwapActivityMetadataUseCase(
            accounts, metadata, GetSwapStatusUseCase(metadata, swaps), backgroundScope, testScheduler.timeSource,
        )
        repeat(3) { poll(ActivityData.BySwap(pendingFirst)) }
        runCurrent()
        coVerify(exactly = 1) { swaps.checkSwapStatus(pendingFirst, any()) }

        selection.value = second
        poll(ActivityData.BySwap(pendingFirst))
        repeat(3) { poll(ActivityData.BySwap(pendingSecond)) }
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        coVerify(exactly = 1) { swaps.checkSwapStatus(pendingFirst, any()) }
        coVerify(exactly = 1) { swaps.checkSwapStatus(pendingSecond, any()) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 2) { swaps.checkSwapStatus(pendingSecond, any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aVisibleSwapRetriesAnUnavailableReadWithoutRedisplaying() = runTest {
        val pending = swap(SwapStatus.PENDING)
        val terminal = swap(SwapStatus.SUCCESS)
        val accounts = mockk<AccountDataSource> {
            every { selectedAccount } returns MutableStateFlow(owner)
        }
        val result = mockk<SwapQuoteStatus>(relaxed = true) { every { status } returns SwapStatus.SUCCESS }
        var readable = false
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(deposit, owner) } coAnswers {
                if (!readable) throw MetadataReadUnavailableException()
                pending
            }
            coEvery { updateSwap(pending, result) } returns terminal
        }
        val swaps = mockk<SwapRepository> { coEvery { checkSwapStatus(pending, any()) } returns result }
        val poll = UpdateSwapActivityMetadataUseCase(
            accounts, metadata, GetSwapStatusUseCase(metadata, swaps), backgroundScope, testScheduler.timeSource,
        )

        poll(ActivityData.BySwap(pending))
        runCurrent()
        coVerify(exactly = 1) { metadata.getSwapMetadata(deposit, owner) }
        coVerify(exactly = 0) { swaps.checkSwapStatus(any(), any()) }

        readable = true
        advanceTimeBy(15_000)
        runCurrent()
        coVerify(exactly = 1) { swaps.checkSwapStatus(pending, any()) }
        advanceTimeBy(16_000)
        runCurrent()
        coVerify(exactly = 2) { metadata.getSwapMetadata(deposit, owner) }
        coVerify(exactly = 1) { swaps.checkSwapStatus(pending, any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openSwapDetailsRecoversItsExactOwnerAfterATemporaryReadFailure() = runTest {
        val pending = swap(SwapStatus.PENDING)
        val terminal = swap(SwapStatus.SUCCESS)
        val result = mockk<SwapQuoteStatus>(relaxed = true) { every { status } returns SwapStatus.SUCCESS }
        var readable = false
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(deposit, owner) } coAnswers {
                if (!readable) throw MetadataReadUnavailableException()
                pending
            }
            coEvery { updateSwap(pending, result) } returns terminal
        }
        val swaps = mockk<SwapRepository> { coEvery { checkSwapStatus(pending, any()) } returns result }
        val status = GetSwapStatusUseCase(metadata, swaps)
        val observations = mutableListOf<SwapQuoteStatusData>()
        backgroundScope.launch { status.observeForAccount(deposit, owner).collect { observations += it } }
        runCurrent()
        assertIs<MetadataReadUnavailableException>(observations.last().error)

        readable = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(terminal, observations.last().savedMetadata)
        assertNull(observations.last().error)
        coVerify(exactly = 1) { swaps.checkSwapStatus(pending, any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aNewObserverCannotUseThePreviousOwnerWhileTheAccountListCatchesUp() = runTest {
        val committed = AccountUuid.new(ByteArray(16) { 11 })
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(owner))
            coEvery { confirmSelection(owner) } returns null
        }
        val preference = mockk<SelectedAccountUUIDProvider> {
            coEvery { captureSelection() } returns SelectedAccountSelection(committed, 1)
            every { isCurrent(any()) } returns true
        }
        val metadata = mockk<MetadataRepository>()
        val swaps = mockk<SwapRepository>()
        val useCase = GetSwapStatusUseCase(metadata, swaps, accounts = accounts, accountSelection = preference)

        val observed = useCase.observeForAccount(deposit, owner).first { !it.isLoading }
        assertNotNull(observed.error)
        coVerify(exactly = 0) { metadata.getSwapMetadata(any(), any()) }
        coVerify(exactly = 0) { swaps.checkSwapStatus(any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun selectedPreferenceRevocationClosesAnInFlightStatusRequestBeforeTheUiChangesOwner() = runTest {
        val pending = swap(SwapStatus.PENDING)
        val selected = mockk<WalletAccount> {
            every { sdkAccount } returns owner.sdkAccount
            every { isSelected } returns true
        }
        val accounts = mockk<AccountDataSource> { every { allAccounts } returns MutableStateFlow(listOf(selected)) }
        var selectionVersion = 0L
        val preference = mockk<SelectedAccountUUIDProvider> {
            coEvery { captureSelection() } returns SelectedAccountSelection(owner.sdkAccount.accountUuid, 0)
            every { isCurrent(any()) } answers { firstArg<SelectedAccountSelection>().version == selectionVersion }
        }
        val metadata = mockk<MetadataRepository> {
            coEvery { getSwapMetadata(deposit, owner) } returns pending
        }
        val transportReady = CompletableDeferred<Unit>()
        val enteredRequest = CompletableDeferred<() -> Boolean>()
        val swaps = mockk<SwapRepository> {
            coEvery { checkSwapStatus(pending, any()) } coAnswers {
                enteredRequest.complete(secondArg())
                transportReady.await()
                error("Synthetic transport stopped")
            }
        }
        val useCase = GetSwapStatusUseCase(metadata, swaps, accounts = accounts, accountSelection = preference)
        val observations = mutableListOf<SwapQuoteStatusData>()
        backgroundScope.launch { useCase.observeForAccount(deposit, owner).collect { observations += it } }
        val canTransmit = enteredRequest.await()

        selectionVersion += 1
        assertFalse(canTransmit())
        transportReady.complete(Unit)
        runCurrent()
        assertNull(observations.last().savedMetadata)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun anExistingTransactionRowGainsAPollIdentityWhenItsSavedSwapAppears() {
        val initial = activity(confirmed, null)
        val associated = activity(confirmed, swap(SwapStatus.PENDING))
        assertEquals(initial.key, associated.key)
        assertNull(initial.pendingSwapReference)
        assertEquals(deposit, associated.pendingSwapReference)
        assertNull(activity(confirmed, swap(SwapStatus.SUCCESS)).pendingSwapReference)
    }

    private fun swap(status: SwapStatus?) =
        TransactionSwapMetadata(
            depositAddress = deposit,
            provider = "near",
            totalFees = Zatoshi(0),
            totalFeesUsd = BigDecimal.ZERO,
            lastUpdated = Instant.EPOCH,
            origin = SwapAssetTestFixture.zecSimpleAsset(),
            destination = SwapAssetTestFixture.simpleAsset(),
            mode = SwapMode.EXACT_INPUT,
            recordedStatus = status,
            amountOutFormatted = BigDecimal.ZERO,
            ownerAccount = owner,
        )

    private fun activity(transaction: Transaction, swap: TransactionSwapMetadata?) =
        ActivityMapper().createTransactionState(
            ActivityData.ByTransaction(transaction, TransactionMetadata(false, true, null, swap)),
            Instant.EPOCH, {}, {}, {}
        )

    private fun assertFailed(activity: ActivityState, title: StringResource) {
        assertEquals(title, activity.title)
        assertEquals(R.drawable.ic_transaction_send_failed, activity.bigIcon)
        assertEquals(StringResourceColor.NEGATIVE, activity.amountColor())
    }

    private fun ActivityState.amountColor() = assertIs<StyledStringResource.ByStringResource>(value).style.color
}
