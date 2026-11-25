package co.electriccoin.zcash.ui.screen.pay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.positiveUsdPrice
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapPriceMarkets
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.canSpend
import co.electriccoin.zcash.ui.common.repository.DEFAULT_SLIPPAGE
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapAssetPickerUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.awaitQuoteOrigin
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.pay.info.PayInfoArgs
import co.electriccoin.zcash.ui.screen.swap.SwapCancelState
import co.electriccoin.zcash.ui.screen.swap.SwapCancelSheetDismissal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Suppress("TooManyFunctions")
internal class PayVM(
    getCuratedSwapAssetsUseCase: GetCuratedSwapAssetsUseCase,
    getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val swapRepository: SwapRepository,
    private val cancelSwap: CancelSwapUseCase,
    private val navigationRouter: NavigationRouter,
    private val requestSwapQuote: RequestSwapQuoteUseCase,
    private val navigateToSwapQuoteIfAvailable: NavigateToSwapQuoteIfAvailableUseCase,
    private val exactOutputVMMapper: ExactOutputVMMapper,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase,
    private val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
    private val isABContactHintVisible: IsABContactHintVisibleUseCase,
    private val getPreselectedSwapAsset: GetPreselectedSwapAssetUseCase,
    private val navigateToSlippage: NavigateToSlippageUseCase,
    private val navigateToSwapAssetPicker: NavigateToSwapAssetPickerUseCase,
    private val navigateToSwapRefundWarning: NavigateToSwapRefundWarningUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    // VM-owned state. The externally-observed `swapAssets`/`account`/`isABHintVisible` fields are
    // injected by the `state` combine below, so the VM only ever updates the fields it owns.
    private val internalState =
        MutableStateFlow(
            InternalStateImpl(
                address = "",
                isABHintVisible = false,
                selectedABContact = null,
                asset = null,
                amount = NumberTextFieldInnerState(),
                fiatAmount = NumberTextFieldInnerState(),
                slippage = DEFAULT_SLIPPAGE,
                isRequestingQuote = false,
                account = null,
                swapAssets = SwapAssetsData(),
                isEphemeralAddressLocked = false
            )
        )

    private val cancelSheet = MutableStateFlow<(() -> Boolean)?>(null)
    private val cancelSheetDismissal = SwapCancelSheetDismissal()
    private var quoteActionInProgress = false
    private var quoteRequestJob: Job? = null
    private var isCancellingQuote = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val isABHintVisible =
        internalState
            .map { it.address to it.selectedABContact }
            .distinctUntilChanged()
            .flatMapLatest { (address, contact) ->
                isABContactHintVisible.observe(selectedContact = contact, text = address)
            }

    val cancelState =
        cancelSheet
            .map { owner ->
                if (owner != null) {
                    SwapCancelState(
                        icon = imageRes(R.drawable.ic_swap_quote_cancel),
                        title = stringRes(R.string.swapAndPay_canceltitle),
                        subtitle = stringRes(R.string.swapAndPay_cancelMsg),
                        negativeButton =
                            ButtonState(
                                text = stringRes(R.string.swapAndPay_cancelSwap),
                                onClick = { onCancelSwapClick(owner) }
                            ),
                        positiveButton =
                            ButtonState(
                                text = stringRes(R.string.swap_cancel_positive),
                                onClick = { onDismissCancelClick(owner) }
                            ),
                        onBack = { onDismissCancelClick(owner) }
                    )
                } else {
                    null
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    // Stable handlers; quote dispatch adds a guard for the state used by each rendered button.
    private fun callbacks(state: InternalStateImpl) =
        ExactOutputStateCallbacks(
            onBack = ::onBack,
            onSwapInfoClick = ::onInfoClick,
            onSwapAssetPickerClick = ::onSwapAssetPickerClick,
            onSlippageClick = ::onSlippageClick,
            onRequestSwapQuoteClick = { amount, fiatAmount, address ->
                onRequestSwapQuoteClick(state, amount, fiatAmount, address)
            },
            onTryAgainClick = ::onTryAgainClick,
            onAddressChange = ::onAddressChange,
            onTokenAmountChange = ::onTokenAmountChange,
            onFiatAmountChange = ::onFiatAmountChange,
            onQrCodeScannerClick = ::onQrCodeScannerClick,
            onAddressBookClick = ::onAddressBookClick,
            onDeleteSelectedContactClick = ::onDeleteSelectedContactClick
        )

    val state =
        combine(
            internalState,
            getCuratedSwapAssetsUseCase.observe(),
            getSelectedWalletAccount.observe(),
            isABHintVisible,
        ) { state, swapAssets, account, isABHintVisible ->
            exactOutputVMMapper.createState(
                internalState =
                    state.copy(
                        swapAssets = swapAssets,
                        account = account,
                        isABHintVisible = isABHintVisible
                    ),
                callbacks = callbacks(state)
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            val asset = getPreselectedSwapAsset()
            internalState.update {
                if (it.selectedAssetId == null) it.selectAsset(asset) else it
            }
        }
        viewModelScope.launch {
            swapRepository.assets.collect { assets ->
                internalState.update { state ->
                    val selected = state.asset
                    val refreshed = SwapPriceMarkets.findAsset(assets.data, state.selectedAssetId)
                    if (refreshed == selected) state else state.withAsset(refreshed)
                }
            }
        }
    }

    private fun onDeleteSelectedContactClick() = internalState.update { it.copy(selectedABContact = null) }

    private fun onTryAgainClick() = swapRepository.requestRefreshAssets()

    private fun onAddressBookClick() =
        viewModelScope.launch {
            val selected = navigateToSelectSwapRecipient(network = internalState.value.asset?.chainTicker)

            if (selected != null) {
                internalState.update {
                    it.clearRequestAmount().copy(
                        address = "", selectedABContact = selected, hasEditedRequestAmount = false
                    )
                }
            }
        }

    private fun onQrCodeScannerClick() =
        viewModelScope.launch {
            if (!canOpenSecondaryScreen()) return@launch
            val result = if (sourceEntryId == null) {
                navigateToScanAddress()
            } else {
                navigateToScanAddress.scanFrom(sourceEntryId)
            }
            if (result != null) {
                if (sourceEntryId == null) navigationRouter.back()
                onAddressChange(result.rawInput ?: result.address)
            }
        }

    private fun onSlippageClick(fiatAmount: BigDecimal?) =
        viewModelScope.launch {
            val newSlippage =
                navigateToSlippage(
                    currentSlippage = internalState.value.slippage,
                    fiatAmount = fiatAmount,
                    mode = SwapMode.EXACT_OUTPUT
                )
            if (newSlippage != null) {
                internalState.update { it.copy(slippage = newSlippage) }
            }
        }

    private fun onBack() =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheetDismissal.isPending || !navigationRouter.isAtEntry(sourceEntryId)) {
                return@launch
            }
            val owner = cancelSheet.value
            when {
                internalState.value.isRequestingQuote -> {
                    if (owner == null) {
                        cancelSheetDismissal.onOpen()
                        cancelSheet.value = requestSwapQuote.captureNavigationOwnership(quoteRequestJob)
                    }
                }
                owner != null -> continueQuote(owner)
                else -> {
                    isCancellingQuote = true
                    try {
                        if (sourceEntryId == null) cancelSwap() else cancelSwap.cancelFrom(sourceEntryId)
                    } finally {
                        isCancellingQuote = false
                    }
                }
            }
        }

    private fun onCancelSwapClick(owner: () -> Boolean) =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheet.value !== owner || !owner()) return@launch
            if (!navigationRouter.isAtEntry(sourceEntryId)) return@launch
            isCancellingQuote = true
            quoteRequestJob?.cancel()
            try {
                cancelSwap.afterDismissal(owner, sourceEntryId) { dismissCancelSheet() }
            } finally {
                isCancellingQuote = false
            }
        }

    private fun onDismissCancelClick(owner: () -> Boolean) =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheet.value !== owner) return@launch
            continueQuote(owner)
        }

    private suspend fun continueQuote(owner: () -> Boolean) {
        dismissCancelSheet()
        navigateToSwapQuoteIfAvailable(owner, sourceEntryId) {}
    }

    private suspend fun dismissCancelSheet() = cancelSheetDismissal.awaitHidden { cancelSheet.value = null }

    fun onCancelSheetHidden() = cancelSheetDismissal.onHidden()

    private fun canNavigateToReview() =
        !isCancellingQuote && cancelSheet.value == null && !cancelSheetDismissal.isPending

    private suspend fun awaitReviewNavigation(): Boolean {
        cancelSheetDismissal.awaitClosed()
        return canNavigateToReview()
    }

    private fun onTokenAmountChange(amount: NumberTextFieldInnerState) {
        internalState.update { state ->
            if (amount.isSelectionOnlyChange) {
                state.copy(amount = state.amount.withSelection(amount.innerTextFieldState.selection))
            } else {
                state.copy(amount = amount, isFiatEdited = false).withEditedAmount()
            }
        }
    }

    private fun onFiatAmountChange(amount: NumberTextFieldInnerState) {
        internalState.update { state ->
            if (amount.isSelectionOnlyChange) {
                state.copy(fiatAmount = state.fiatAmount.withSelection(amount.innerTextFieldState.selection))
            } else {
                state.copy(fiatAmount = amount, isFiatEdited = true).withEditedAmount()
            }
        }
    }

    private fun NumberTextFieldInnerState.withSelection(selection: TextSelection) =
        copy(innerTextFieldState = innerTextFieldState.copy(selection = selection))

    private fun InternalStateImpl.withEditedAmount() =
        copy(hasEditedRequestAmount = true, requestAmountAssetId = null).recomputeLinkedAmount()

    /** Gates the quote request behind the refund warning before the CTA starts spinning. */
    private fun onRequestSwapQuoteClick(
        snapshot: InternalStateImpl,
        amount: BigDecimal,
        fiatAmount: BigDecimal?,
        address: String,
    ) =
        viewModelScope.launch {
            if (!canStartQuote(snapshot)) return@launch
            val asset = snapshot.asset ?: return@launch
            if (asset.positiveUsdPrice == null || swapRepository.assets.value.zecAsset.positiveUsdPrice == null) {
                return@launch
            }
            val slippage = snapshot.slippage
            val contactNetwork = snapshot.selectedABContact?.blockchain?.chainTicker
            val currentAddress = snapshot.selectedABContact?.address ?: snapshot.address
            if (currentAddress != address || !validateSwapRecipient(
                    address, asset.chainTicker, asset.tokenTicker, contactNetwork, tokenContract = asset.contractAddress
                ).isValid
            ) return@launch
            quoteActionInProgress = true
            try {
                if (!navigateToSwapRefundWarning(fiatAmount, SwapMode.EXACT_OUTPUT, sourceEntryId)) {
                    return@launch
                }
                if (!navigationRouter.awaitQuoteOrigin(sourceEntryId)) return@launch
                if (isCancellingQuote) return@launch
                if (!hasSameQuoteInputs(snapshot)) return@launch
                quoteRequestJob = coroutineContext.job
                internalState.update { it.copy(isRequestingQuote = true) }
                requestSwapQuote.requestExactOutput(
                    amount = amount,
                    address = address,
                    selectedAsset = asset,
                    slippage = slippage,
                    canNavigateToSwapQuote = ::awaitReviewNavigation,
                    contactNetwork = contactNetwork,
                    sourceEntryId = sourceEntryId,
                )
            } finally {
                quoteRequestJob = null
                quoteActionInProgress = false
                internalState.update { it.copy(isRequestingQuote = false) }
            }
        }

    private fun canStartQuote(snapshot: InternalStateImpl): Boolean =
        internalState.value === snapshot && !snapshot.isRequestingQuote &&
            !quoteActionInProgress && canNavigateToReview()

    private fun hasSameQuoteInputs(snapshot: InternalStateImpl): Boolean {
        val current = internalState.value
        return current.address == snapshot.address && current.selectedABContact == snapshot.selectedABContact &&
            current.asset?.assetId == snapshot.asset?.assetId && current.amount == snapshot.amount
    }

    private fun onInfoClick() {
        if (canOpenSecondaryScreen()) navigationRouter.forward(PayInfoArgs)
    }

    internal fun canOpenSecondaryScreen() = !quoteActionInProgress && canNavigateToReview()

    private fun onAddressChange(new: String) =
        internalState.update { state ->
            val edited = (if (new == state.address) state else state.clearRequestAmount()).copy(
                selectedABContact = null,
                address = new,
                hasEditedRequestAmount = state.hasEditedRequestAmount && new == state.address
            )
            val withRequestAmount = edited.applyRequestAmount()
            if (withRequestAmount == edited) edited else withRequestAmount.recomputeLinkedAmount()
        }

    private fun onSwapAssetPickerClick() =
        viewModelScope.launch {
            val asset = navigateToSwapAssetPicker()
            if (asset != null) {
                internalState.update { it.selectAsset(asset) }
            }
        }

    private fun InternalStateImpl.selectAsset(asset: SwapAsset): InternalStateImpl =
        copy(selectedAssetId = asset.assetId)
            .withAsset(SwapPriceMarkets.findAsset(swapRepository.assets.value.data, asset.assetId))

    /** Keeps the last edited denomination while updating the derived field at the current price. */
    private fun InternalStateImpl.withAsset(asset: SwapAsset?): InternalStateImpl {
        val updated = copy(asset = asset)
        val amounts = if (asset?.assetId == this.asset?.assetId) {
            updated
        } else {
            updated.clearRequestAmount(keepBinding = true).applyRequestAmount()
        }
        return amounts.recomputeLinkedAmount()
    }

    private fun InternalStateImpl.applyRequestAmount(): InternalStateImpl {
        val selected = asset
        val canPrefill = requestAmountAssetId == null ||
            (requestAmountAssetId == selected?.assetId && amount.amount == null)
        val canUseAddress = !hasEditedRequestAmount && selectedABContact == null
        if (selected == null || !canPrefill || !canUseAddress) return this

        val recipient = validateSwapRecipient(
            address, selected.chainTicker, selected.tokenTicker, tokenContract = selected.contractAddress
        )
        return recipient.input?.amount?.takeIf { recipient.isValid }?.let { requested ->
            copy(
                amount = NumberTextFieldInnerState.fromAmount(requested),
                fiatAmount = NumberTextFieldInnerState(),
                isFiatEdited = false,
                requestAmountAssetId = selected.assetId
            )
        } ?: this
    }

    private fun InternalStateImpl.clearRequestAmount(keepBinding: Boolean = false): InternalStateImpl =
        if (requestAmountAssetId == null) this else copy(
            amount = NumberTextFieldInnerState(),
            fiatAmount = NumberTextFieldInnerState(),
            requestAmountAssetId = requestAmountAssetId.takeIf { keepBinding }
        )

    private fun InternalStateImpl.recomputeLinkedAmount(): InternalStateImpl =
        if (isFiatEdited) {
            copy(
                amount =
                    exactOutputVMMapper.createAmountInnerState(
                        fiatInnerState = fiatAmount,
                        amountInnerState = amount,
                        asset = asset
                    )
            )
        } else {
            copy(
                fiatAmount =
                    exactOutputVMMapper.createFiatAmountInnerState(
                        amountInnerState = amount,
                        fiatInnerState = fiatAmount,
                        asset = asset
                    )
            )
        }
}

internal interface InternalState {
    val address: String
    val isABHintVisible: Boolean
    val selectedABContact: EnhancedABContact?
    val asset: SwapAsset?
    val amount: NumberTextFieldInnerState
    val fiatAmount: NumberTextFieldInnerState
    val slippage: BigDecimal
    val isRequestingQuote: Boolean
    val account: WalletAccount?
    val swapAssets: SwapAssetsData
    val isEphemeralAddressLocked: Boolean

    /**
     * Delegates to the shared [co.electriccoin.zcash.ui.common.model.canSpend] primitive.
     * Re-evaluated whenever the selected account emits a new balance. Null while the balance has
     * not loaded yet.
     */
    fun canSpend(amount: Zatoshi): Boolean? = account.canSpend(amount)
}

internal data class InternalStateImpl(
    override val address: String,
    override val isABHintVisible: Boolean,
    override val selectedABContact: EnhancedABContact?,
    override val asset: SwapAsset?,
    override val amount: NumberTextFieldInnerState,
    override val fiatAmount: NumberTextFieldInnerState,
    override val slippage: BigDecimal,
    override val isRequestingQuote: Boolean,
    override val account: WalletAccount?,
    override val swapAssets: SwapAssetsData,
    override val isEphemeralAddressLocked: Boolean,
    val selectedAssetId: String? = null,
    val isFiatEdited: Boolean = false,
    val hasEditedRequestAmount: Boolean = false,
    // The current address supplied the amount in this denomination.
    val requestAmountAssetId: String? = null,
) : InternalState
