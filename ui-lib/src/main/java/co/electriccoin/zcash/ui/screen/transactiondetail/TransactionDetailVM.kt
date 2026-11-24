package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.TransactionPool
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zip318Kind
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.mapper.savedSwapSupportButton
import co.electriccoin.zcash.ui.common.model.positiveRefund
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.DetailedTransactionData
import co.electriccoin.zcash.ui.common.usecase.ActivityDetailUnavailableException
import co.electriccoin.zcash.ui.common.usecase.GetTransactionDetailByIdUseCase
import co.electriccoin.zcash.ui.common.usecase.MarkTxMemoAsReadUseCase
import co.electriccoin.zcash.ui.common.usecase.SendTransactionAgainUseCase
import co.electriccoin.zcash.ui.common.usecase.SetTransactionBookmarkUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.TickerLocation.HIDDEN
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ReceiveTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendShieldedState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.toDetailStatusRow
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendTransparentState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.ShieldingState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailInfoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemoState
import co.electriccoin.zcash.ui.screen.transactiondetail.info.TransactionDetailMemosState
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.loggableNot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class TransactionDetailVM(
    getTransactionDetailById: GetTransactionDetailByIdUseCase,
    private val markTxMemoAsRead: MarkTxMemoAsReadUseCase,
    private val transactionDetailArgs: TransactionDetailArgs,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendTransactionAgain: SendTransactionAgainUseCase,
    private val setTransactionBookmark: SetTransactionBookmarkUseCase,
    private val mapper: CommonTransactionDetailMapper,
    private val getSwapMessage: SwapSupportMapper,
) : ViewModel() {
    val log = loggableNot("TransactionDetailVM")
    private var bookmarkJob: Job? = null
    private val transaction =
        getTransactionDetailById
            .observe(transactionDetailArgs.transactionId, transactionDetailArgs.accountKeyId)
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                replay = 1,
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state =
        transaction
            .mapLatest { snapshot ->
                if (snapshot == null) return@mapLatest createEmptyState().copy(
                    errorFooter = mapper.createTransactionDetailErrorFooter(ActivityDetailUnavailableException()),
                )
                val recorded = snapshot.metadata.swapMetadata
                val transaction = if (recorded != null && snapshot.swap?.savedMetadata != null) {
                    snapshot.copy(swap = snapshot.swap.withSavedObservation(recorded))
                } else {
                    snapshot
                }
                val info = createTransactionInfoState(transaction)
                TransactionDetailState(
                    explorerLinks = createExplorerLinks(transaction),
                    onBack = ::onBack,
                    info = info,
                    header = createTransactionHeaderState(transaction, info),
                    primaryButton = createPrimaryButtonState(transaction),
                    secondaryButton = createSecondaryButtonState(transaction),
                    bookmarkButton =
                        IconButtonState(
                            icon =
                                if (transaction.metadata.isBookmarked) {
                                    R.drawable.ic_transaction_detail_bookmark
                                } else {
                                    R.drawable.ic_transaction_detail_no_bookmark
                                },
                            onClick = { onBookmarkClick(transaction) },
                            hapticFeedbackType =
                                if (transaction.metadata.isBookmarked) {
                                    HapticFeedbackType.ToggleOff
                                } else {
                                    HapticFeedbackType.ToggleOn
                                }
                        ).takeIf {
                            transaction.transaction.accountUuid != null &&
                                transaction.metadata.bookmarkWriteToken != null
                        },
                    errorFooter = createErrorFooter(transaction),
                    infoFooter =
                        stringRes(R.string.deposits_info)
                            .takeIf {
                                val sendSwap = info as? SendSwapState
                                transaction.swap?.status?.status == PENDING &&
                                    sendSwap?.statusRow?.status == PENDING && !sendSwap.statusRow.fundingNotSubmitted
                            }
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createEmptyState()
            )

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val transaction = transaction.filterNotNull().first()
                val accountUuid = transaction.transaction.accountUuid
                val token = transaction.metadata.bookmarkWriteToken
                if (transaction.transaction.memoCount > 0 && accountUuid != null && token != null) {
                    markTxMemoAsRead(transactionDetailArgs.transactionId, accountUuid, token)
                }
            }
        }
    }

    private fun createEmptyState(): TransactionDetailState =
        TransactionDetailState(
            onBack = ::onBack,
            bookmarkButton = null,
            header =
                TransactionDetailHeaderState(
                    title = null,
                    amount = null,
                    icons =
                        listOf(
                            loadingImageRes(),
                            loadingImageRes(),
                            loadingImageRes(),
                        )
                ),
            info = null,
            errorFooter = null,
            infoFooter = null,
            primaryButton = null,
            secondaryButton = null,
        )

    private fun createExplorerLinks(data: DetailedTransactionData): List<TransactionExplorerLink> {
        val testnet = co.electriccoin.zcash.ui.common.model.VersionInfo.NETWORK ==
            cash.z.ecc.android.sdk.model.ZcashNetwork.Testnet
        val deposit = explorerLinks(
            chain = "zec",
            ids = listOf(data.transaction.id.txIdString()),
            label = stringRes(
                if (data.swap == null) R.string.private_explorer_transaction else R.string.private_explorer_zec_deposit
            ),
            testnet = testnet,
            router = navigationRouter
        )
        val status = data.swap?.status ?: return deposit
        val otherOriginTransactions = explorerLinks(
            chain = status.originAsset.blockchain.chainTicker,
            ids = status.originTransactionIds.filterNot {
                it.equals(data.transaction.id.txIdString(), ignoreCase = true)
            },
            label = stringRes(R.string.private_explorer_deposit, status.originAsset.chainName),
            testnet = testnet,
            router = navigationRouter
        )
        return deposit + otherOriginTransactions + explorerLinks(
            chain = status.destinationAsset.blockchain.chainTicker,
            ids = status.destinationTransactionIds,
            label = stringRes(R.string.private_explorer_destination, status.destinationAsset.chainName),
            testnet = testnet,
            router = navigationRouter
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun createTransactionInfoState(transaction: DetailedTransactionData): TransactionDetailInfoState {
        log("createTransactionInfoState ${transaction.transaction}")
        return when (transaction.transaction) {
            is SendTransaction -> {
                when {
                    transaction.swap != null -> {
                        val recipient =
                            transaction.swap.status
                                ?.destinationAddress
                                ?.address
                        val saved = transaction.swap.savedMetadata ?: transaction.metadata.swapMetadata
                        val statusOverride = saved?.outgoingStatus(
                            transaction.transaction, transaction.swap.status?.status
                        ).takeIf {
                            transaction.transaction is SendTransaction.Failed || saved?.fundingNotSubmitted == true
                        }
                        SendSwapState(
                            statusRow = transaction.swap.toDetailStatusRow(
                                metadata = saved,
                                outgoingStatus = statusOverride,
                            ),
                            message = getSwapMessage.getMessage(
                                transaction.swap.status, saved, transaction.swap.data.isFundingWindowOpen
                            ),
                            quoteHeader =
                                mapper.createTransactionDetailQuoteHeaderState(
                                    swap = transaction.swap.status,
                                    originAsset = transaction.swap.originAsset,
                                    destinationAsset = transaction.swap.destinationAsset,
                                    savedMetadata = transaction.swap.savedMetadata,
                                ),
                            depositAddress =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            totalFees =
                                transaction.transaction.fee
                                    ?.let { stringRes(it) },
                            recipientAddress = recipient?.let { stringResByAddress(it) },
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            refundedAmount =
                                transaction.swap.status
                                    ?.positiveRefund
                                    ?.let {
                                        stringResByCurrencyNumber(amount = it, ticker = CURRENCY_TICKER)
                                    },
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onDepositAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            onRecipientAddressClick =
                                if (recipient == null) {
                                    null
                                } else {
                                    { onCopyToClipboard(recipient) }
                                },
                            maxSlippage =
                                transaction.swap.status?.maxSlippage?.let {
                                    stringResByNumber(it, 0) + stringRes("%")
                                },
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isSlippageRealized = transaction.swap.status?.isSlippageRealized == true,
                            isPending = isPending(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                            donationBasisZec = transaction.transaction.fee?.let { fee ->
                                (transaction.transaction.amount - fee).value
                                    .toBigDecimal().movePointLeft(ZEC_DECIMAL_PLACES)
                                    .subtract(transaction.swap.status?.refundedFormatted ?: java.math.BigDecimal.ZERO)
                                    .takeIf {
                                        !isPending(transaction) && it.signum() > 0 &&
                                            transaction.swap.status?.status ==
                                            co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
                                    }?.toPlainString()
                            },
                        )
                    }

                    transaction.recipient is WalletAddress.Transparent -> {
                        SendTransparentState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address = stringRes(transaction.recipient.address),
                            addressAbbreviated = stringResByAddress(transaction.recipient.address),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = { onCopyToClipboard(transaction.recipient.address) },
                            fee = createFeeStringRes(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                            isPending = isPending(transaction),
                            note = transaction.metadata.note?.let { stringRes(it) },
                        )
                    }

                    else -> {
                        SendShieldedState(
                            contact = transaction.contact?.let { stringRes(it.name) },
                            address =
                                stringResByAddress(
                                    value = transaction.recipient?.address.orEmpty()
                                ),
                            transactionId =
                                stringResByTransactionId(
                                    value = transaction.transaction.id.txIdString(),
                                    abbreviated = true
                                ),
                            onTransactionIdClick = {
                                onCopyToClipboard(transaction.transaction.id.txIdString())
                            },
                            onTransactionAddressClick = {
                                onCopyToClipboard(transaction.recipient?.address.orEmpty())
                            },
                            fee = createFeeStringRes(transaction),
                            completedTimestamp = createTimestampStringRes(transaction),
                            memo =
                                TransactionDetailMemosState(
                                    transaction.memos.orEmpty().map { memo ->
                                        TransactionDetailMemoState(
                                            content = stringRes(memo),
                                            onClick = { onCopyToClipboard(memo) }
                                        )
                                    }
                                ),
                            note = transaction.metadata.note?.let { stringRes(it) },
                            isPending = isPending(transaction)
                        )
                    }
                }
            }

            is ReceiveTransaction -> {
                if (transaction.transaction.transactionOutputs.all { it.pool == TransactionPool.TRANSPARENT }) {
                    ReceiveTransparentState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction)
                    )
                } else {
                    ReceiveShieldedState(
                        transactionId =
                            stringResByTransactionId(
                                value = transaction.transaction.id.txIdString(),
                                abbreviated = true
                            ),
                        onTransactionIdClick = {
                            onCopyToClipboard(transaction.transaction.id.txIdString())
                        },
                        completedTimestamp = createTimestampStringRes(transaction),
                        memo =
                            TransactionDetailMemosState(
                                transaction.memos?.map { memo ->
                                    TransactionDetailMemoState(
                                        content = stringRes(memo),
                                        onClick = { onCopyToClipboard(memo) }
                                    )
                                }
                            ),
                        note = transaction.metadata.note?.let { stringRes(it) },
                        isPending = isPending(transaction)
                    )
                }
            }

            is ShieldTransaction -> {
                ShieldingState(
                    transactionId =
                        stringResByTransactionId(
                            value = transaction.transaction.id.txIdString(),
                            abbreviated = true
                        ),
                    onTransactionIdClick = {
                        onCopyToClipboard(transaction.transaction.id.txIdString())
                    },
                    completedTimestamp = createTimestampStringRes(transaction),
                    fee = createFeeStringRes(transaction),
                    note = transaction.metadata.note?.let { stringRes(it) },
                    isPending = isPending(transaction)
                )
            }
        }
    }

    private fun createFeeStringRes(data: DetailedTransactionData): StringResource {
        val feePaid =
            data.transaction.fee.takeIf { data.transaction !is ReceiveTransaction }
                ?: return stringRes(R.string.transaction_detail_fee_minimal, CURRENCY_TICKER)

        return stringRes(feePaid)
    }

    private fun createTimestampStringRes(data: DetailedTransactionData) =
        mapper.createTransactionDetailTimestamp(data.transaction.timestamp)

    private fun isPending(data: DetailedTransactionData) = data.transaction.timestamp == null

    private fun onCopyToClipboard(text: String) {
        copyToClipboard(
            value = text
        )
    }

    private fun createErrorFooter(data: DetailedTransactionData): ErrorFooter? =
        mapper.createTransactionDetailErrorFooter(data.swap?.error)

    private fun createPrimaryButtonState(data: DetailedTransactionData): ButtonState? {
        val supportButton =
            getSwapMessage.getButton(data.swap?.status) {
                onContactSupport(it)
            }
        return when {
            supportButton != null -> {
                supportButton
            }

            data.swap?.error != null -> {
                mapper.createTransactionDetailErrorButtonState(
                    error = data.swap.error,
                    reloadHandle = data.reloadHandle
                )
            }

            data.swap != null -> {
                data.swap.savedMetadata?.savedSwapSupportButton(::onContactSupport)
            }

            data.contact == null -> {
                if (data.transaction is SendTransaction) {
                    ButtonState(
                        text = stringRes(R.string.transactionHistory_saveAddress),
                        onClick = { onSaveAddressClick(data) }
                    )
                } else {
                    null
                }
            }

            else -> {
                if (data.transaction is SendTransaction) {
                    ButtonState(
                        text = stringRes(R.string.transactionHistory_sendAgain),
                        onClick = { onSendAgainClick(data) }
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun onContactSupport(depositAddress: String) {
        navigationRouter.forward(SwapSupportArgs(depositAddress))
    }

    private fun createSecondaryButtonState(transaction: DetailedTransactionData): ButtonState? {
        fun createAddNoteButtonState(): ButtonState? {
            val accountUuid = transaction.transaction.accountUuid
            val token = transaction.metadata.bookmarkWriteToken
            if (accountUuid == null || token == null) return null
            return ButtonState(
                text =
                    if (transaction.metadata.note != null) {
                        stringRes(R.string.transaction_detail_edit_note)
                    } else {
                        stringRes(R.string.annotation_addArticle)
                    },
                onClick = {
                    navigationRouter.forward(
                        TransactionNote(transactionDetailArgs.transactionId, accountUuid.toStorageKeyId(), token)
                    )
                }
            )
        }

        return when {
            transaction.swap?.error != null && transaction.swap.status == null ->
                (transaction.swap.savedMetadata ?: transaction.metadata.swapMetadata)
                    ?.savedSwapSupportButton(::onContactSupport)

            transaction.swap != null &&
                transaction.swap.error == null &&
                transaction.swap.status?.status == SUCCESS
            -> createAddNoteButtonState()

            transaction.swap != null -> null

            else -> createAddNoteButtonState()
        }
    }

    private fun onSaveAddressClick(transaction: DetailedTransactionData) {
        transaction.recipient?.let {
            navigationRouter.forward(AddZashiABContactArgs(it.address))
        }
    }

    private fun onSendAgainClick(transaction: DetailedTransactionData) =
        viewModelScope.launch { sendTransactionAgain(transaction) }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun createTransactionHeaderState(
        data: DetailedTransactionData,
        info: TransactionDetailInfoState
    ): TransactionDetailHeaderState =
        TransactionDetailHeaderState(
            title =
                when (val transaction = data.transaction) {
                    is ReceiveTransaction.Success -> {
                        stringRes(R.string.transaction_received)
                    }

                    is ReceiveTransaction.Pending -> {
                        stringRes(R.string.transaction_receiving)
                    }

                    is ReceiveTransaction.Failed -> {
                        stringRes(R.string.transaction_history_receiving_failed)
                    }

                    is ShieldTransaction.Success -> {
                        stringRes(R.string.transaction_shieldedFunds)
                    }

                    is ShieldTransaction.Pending -> {
                        stringRes(R.string.transaction_shieldingFunds)
                    }

                    is ShieldTransaction.Failed -> {
                        stringRes(R.string.transaction_failedShieldedFunds)
                    }

                    is SendTransaction -> {
                        if (data.metadata.swapMetadata == null) {
                            when (transaction.overview.zip318Kind) {
                                Zip318Kind.PREPARATION -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> stringRes(R.string.transaction_noteSplit)
                                        is SendTransaction.Pending -> stringRes(R.string.transaction_noteSplitting)
                                        is SendTransaction.Failed -> stringRes(R.string.transaction_noteSplitFailed)
                                    }
                                }

                                Zip318Kind.TRANSFER -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> stringRes(R.string.transaction_migrated)
                                        is SendTransaction.Pending -> stringRes(R.string.transaction_migrating)
                                        is SendTransaction.Failed -> stringRes(R.string.transaction_migrationFailed)
                                    }
                                }

                                Zip318Kind.NOT_CLASSIFIED, Zip318Kind.NONCONFORMING -> {
                                    when (transaction) {
                                        is SendTransaction.Success -> {
                                            stringRes(R.string.transaction_sent)
                                        }

                                        is SendTransaction.Pending -> {
                                            stringRes(R.string.transaction_sending)
                                        }

                                        is SendTransaction.Failed -> {
                                            stringRes(R.string.transaction_history_sending_failed)
                                        }
                                    }
                                }
                            }
                        } else {
                            val saved = data.swap?.savedMetadata ?: data.metadata.swapMetadata
                            val swapStatus = saved.outgoingStatus(transaction, data.swap?.status?.status)
                            when (saved.mode) {
                                EXACT_INPUT -> {
                                    when (swapStatus) {
                                        PROCESSING -> stringRes(R.string.swapStatus_swapping)

                                        PENDING -> stringRes(
                                            if (saved.fundingNotSubmitted) {
                                                R.string.private_swap_funding_not_sent
                                            } else {
                                                R.string.swapStatus_swapping
                                            }
                                        )

                                        INCOMPLETE_DEPOSIT -> stringRes(R.string.swapStatus_swapIncomplete)

                                        SUCCESS -> stringRes(R.string.swapStatus_swapped)

                                        REFUNDED -> stringRes(R.string.swapStatus_swapRefunded)

                                        FAILED -> stringRes(R.string.swapStatus_swapFailed)

                                        EXPIRED -> stringRes(R.string.swapAndPay_expiredTitle)
                                    }
                                }

                                EXACT_OUTPUT -> {
                                    when (swapStatus) {
                                        PROCESSING -> stringRes(R.string.swapStatus_paying)

                                        PENDING -> stringRes(
                                            if (saved.fundingNotSubmitted) {
                                                R.string.private_swap_funding_not_sent
                                            } else {
                                                R.string.swapStatus_paying
                                            }
                                        )

                                        INCOMPLETE_DEPOSIT -> stringRes(R.string.swapStatus_paymentIncomplete)

                                        SUCCESS -> stringRes(R.string.swapStatus_paid)

                                        REFUNDED -> stringRes(R.string.swapStatus_paymentRefunded)

                                        FAILED -> stringRes(R.string.swapStatus_paymentFailed)

                                        EXPIRED -> stringRes(R.string.swapStatus_paymentExpired)
                                    }
                                }

                                FLEX_INPUT -> {
                                    throw UnsupportedOperationException("FLEX_INPUT not supported")
                                }
                            }
                        }
                    }
                },
            amount =
                stringRes(data.transaction.amount, HIDDEN),
            icons =
                when (info) {
                    is ReceiveShieldedState,
                    is ReceiveTransparentState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_received)
                        )
                    }

                    is SendSwapState -> {
                        listOf(
                            data.metadata.swapMetadata
                                ?.origin
                                ?.tokenIcon ?: loadingImageRes(),
                            when (data.metadata.swapMetadata?.mode) {
                                FLEX_INPUT -> throw UnsupportedOperationException("FLEX_INPUT not supported")
                                EXACT_INPUT -> imageRes(R.drawable.ic_transaction_sent)
                                EXACT_OUTPUT -> imageRes(R.drawable.ic_transaction_paid)
                                null -> imageRes(R.drawable.ic_transaction_sent)
                            },
                            data.metadata.swapMetadata
                                ?.destination
                                ?.tokenIcon ?: loadingImageRes()
                        )
                    }

                    is SendShieldedState,
                    is SendTransparentState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(
                                when (data.transaction.overview.zip318Kind) {
                                    Zip318Kind.PREPARATION,
                                    Zip318Kind.TRANSFER -> R.drawable.ic_transaction_migration

                                    Zip318Kind.NOT_CLASSIFIED,
                                    Zip318Kind.NONCONFORMING -> R.drawable.ic_transaction_sent
                                }
                            )
                        )
                    }

                    is ShieldingState -> {
                        listOf(
                            imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                            imageRes(R.drawable.ic_transaction_shielded),
                        )
                    }
                },
        )

    private fun onBack() = navigationRouter.back()

    private fun onBookmarkClick(data: DetailedTransactionData) {
        val accountUuid = data.transaction.accountUuid
        val token = data.metadata.bookmarkWriteToken
        if (bookmarkJob?.isActive != true && accountUuid != null && token != null) {
            bookmarkJob = viewModelScope.launch {
                setTransactionBookmark(
                    data.transaction.id.txIdString(), !data.metadata.isBookmarked, accountUuid, token
                )
            }
        }
    }
}

private const val ZEC_DECIMAL_PLACES = 8
