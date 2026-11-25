package co.electriccoin.zcash.ui.screen.swap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.positiveUsdPrice
import co.electriccoin.zcash.ui.common.model.SwapDirection
import co.electriccoin.zcash.ui.common.model.SwapDirection.SWAP_FROM_ZEC
import co.electriccoin.zcash.ui.common.model.SwapDirection.SWAP_INTO_ZEC
import co.electriccoin.zcash.ui.common.model.SwapPriceMarkets
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.canSpend
import co.electriccoin.zcash.ui.common.model.toSwapMode
import co.electriccoin.zcash.ui.common.repository.DEFAULT_SLIPPAGE
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectABSwapRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSlippageUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapAssetPickerUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapInfoUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapQuoteIfAvailableUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.InnerTextFieldState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.screen.swap.info.SwapRefundAddressInfoArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

private const val ZEC_DECIMALS = 8

@Suppress("TooManyFunctions")
internal class SwapVM(
    private val getCuratedSwapAssetsUseCase: GetCuratedSwapAssetsUseCase,
    getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val getPreselectedSwapAsset: GetPreselectedSwapAssetUseCase,
    private val swapRepository: SwapRepository,
    private val navigateToSwapInfo: NavigateToSwapInfoUseCase,
    private val cancelSwap: CancelSwapUseCase,
    private val navigationRouter: NavigationRouter,
    private val requestSwapQuote: RequestSwapQuoteUseCase,
    private val navigateToSwapQuoteIfAvailable: NavigateToSwapQuoteIfAvailableUseCase,
    private val swapVMMapper: SwapVMMapper,
    private val navigateToScanAddress: NavigateToScanGenericAddressUseCase,
    private val navigateToSelectSwapRecipient: NavigateToSelectABSwapRecipientUseCase,
    private val navigateToSlippage: NavigateToSlippageUseCase,
    private val navigateToSwapAssetPicker: NavigateToSwapAssetPickerUseCase,
    private val navigateToSwapRefundWarning: NavigateToSwapRefundWarningUseCase,
) : ViewModel() {
    // VM-owned state. The externally-observed `swapAssets`/`account` fields are injected by the
    // `state` combine below, so the VM only ever updates the fields it owns.
    private val internalState =
        MutableStateFlow(
            InternalStateImpl(
                account = null,
                swapAsset = null,
                currencyType = CurrencyType.TOKEN,
                amountTextState = NumberTextFieldInnerState(),
                addressText = "",
                slippage = DEFAULT_SLIPPAGE,
                swapAssets = SwapAssetsData(),
                isRequestingQuote = false,
                selectedContact = null,
                swapDirection = SWAP_INTO_ZEC,
                isEphemeralAddressLocked = false
            )
        )

    private val cancelSheet = MutableStateFlow<(() -> Boolean)?>(null)
    private val cancelSheetDismissal = SwapCancelSheetDismissal()
    private var quoteActionInProgress = false
    private var quoteRequestJob: Job? = null
    private var isCancellingQuote = false

    /**
     * Whether the selected account holds spend authority, null until it has loaded; tracked so the
     * direction toggle and the quote request can refuse the swap-from-ZEC direction for an account
     * without it.
     */
    private val isSelectedAccountSpendable = MutableStateFlow<Boolean?>(null)

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
                                text = stringRes(R.string.swapAndPay_cancelPayment),
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

    val state =
        combine(
            internalState,
            getCuratedSwapAssetsUseCase.observe(),
            getSelectedWalletAccount.observe(),
        ) { state, swapAssets, account ->
            createState(
                state.copy(
                    swapAssets = swapAssets,
                    account = account,
                    swapDirection = state.swapDirection.coerceFor(account?.isSpendable)
                )
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            getSelectedWalletAccount
                .observe()
                .map { it?.isSpendable }
                .distinctUntilChanged()
                .collect { isSpendable ->
                    isSelectedAccountSpendable.update { isSpendable }
                    internalState.update { it.copy(swapDirection = it.swapDirection.coerceFor(isSpendable)) }
                }
        }
        viewModelScope.launch {
            val asset = getPreselectedSwapAsset()
            internalState.update {
                if (it.selectedAssetId == null) it.selectAsset(asset) else it
            }
        }
        viewModelScope.launch {
            swapRepository.assets.collect { assets ->
                internalState.update { state ->
                    state.copy(swapAsset = SwapPriceMarkets.findAsset(assets.data, state.selectedAssetId))
                }
            }
        }
    }

    // Stable handlers; quote dispatch adds a guard for the state used by each rendered button.
    private val callbacks =
        SwapStateCallbacks(
            onBack = ::onBack,
            onSwapInfoClick = ::onSwapInfoClick,
            onSwapAssetPickerClick = ::onSwapAssetPickerClick,
            onSwapCurrencyTypeClick = ::onSwapCurrencyTypeClick,
            onSlippageClick = ::onSlippageClick,
            onRequestSwapQuoteClick = { amount, fiatAmount, address ->
                onRequestSwapQuoteClick(internalState.value, amount, fiatAmount, address)
            },
            onTryAgainClick = ::onTryAgainClick,
            onAddressChange = ::onAddressChange,
            onTextFieldChange = ::onTextFieldChange,
            onQrCodeScannerClick = ::onQrCodeScannerClick,
            onAddressBookClick = ::onAddressBookClick,
            onDeleteSelectedContactClick = ::onDeleteSelectedContactClick,
            onBalanceButtonClick = ::onBalanceButtonClick,
            onChangeButtonClick = ::onChangeButtonClick,
            onAddressClick = ::onAddressClick
        )

    private fun createState(rendered: InternalStateImpl): SwapState =
        swapVMMapper.createState(
            internalState = rendered,
            callbacks = callbacks.copy(
                onRequestSwapQuoteClick = { amount, fiatAmount, address ->
                    onRequestSwapQuoteClick(rendered, amount, fiatAmount, address)
                }
            )
        )

    private fun onBalanceButtonClick() {
        // navigationRouter.forward(SpendableBalanceArgs)
    }

    private fun onChangeButtonClick() {
        if (isSelectedAccountSpendable.value != true) return
        internalState.update {
            it.copy(
                swapDirection =
                    when (it.swapDirection) {
                        SWAP_FROM_ZEC -> SWAP_INTO_ZEC
                        SWAP_INTO_ZEC -> SWAP_FROM_ZEC
                    }
            )
        }
    }

    private fun onAddressClick() {
        val asset = internalState.value.swapAsset
        navigationRouter.forward(
            SwapRefundAddressInfoArgs(tokenTicker = asset?.tokenTicker, chainTicker = asset?.chainTicker)
        )
    }

    private fun onDeleteSelectedContactClick() = internalState.update { it.copy(selectedContact = null) }

    private fun onTryAgainClick() = swapRepository.requestRefreshAssets()

    private fun onAddressBookClick() =
        viewModelScope.launch {
            val selected = navigateToSelectSwapRecipient(network = internalState.value.swapAsset?.chainTicker)

            if (selected != null) {
                internalState.update { it.copy(selectedContact = selected, addressText = "") }
                preselectChain(selected)
            }
        }

    private fun preselectChain(selected: EnhancedABContact) {
        if (internalState.value.swapAsset != null) return

        val selectedChainTicker = selected.blockchain?.chainTicker
        val currentChainTicker = internalState.value.swapAsset?.chainTicker

        // Only proceed if the chain ticker changed.
        if (selectedChainTicker != null && !selectedChainTicker.equals(currentChainTicker, ignoreCase = true)) {
            val matchingAssets =
                getCuratedSwapAssetsUseCase()
                    .data
                    ?.filter { asset -> asset.chainTicker.equals(selectedChainTicker, ignoreCase = true) }
                    .orEmpty()
            matchingAssets.singleOrNull()?.let { asset -> internalState.update { it.selectAsset(asset) } }
        }
    }

    private fun onQrCodeScannerClick() =
        viewModelScope.launch {
            val result = navigateToScanAddress()
            if (result != null) {
                navigationRouter.back()
                internalState.update {
                    it.copy(
                        selectedContact = null,
                        addressText = result.rawInput ?: result.address
                    )
                }
            }
        }

    private fun onSlippageClick(fiatAmount: BigDecimal?) =
        viewModelScope.launch {
            val newSlippage =
                navigateToSlippage(
                    currentSlippage = internalState.value.slippage,
                    fiatAmount = fiatAmount,
                    mode = internalState.value.swapDirection.toSwapMode()
                )
            if (newSlippage != null) {
                internalState.update { it.copy(slippage = newSlippage) }
            }
        }

    private fun onBack() =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheetDismissal.isPending) return@launch
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
                    cancelSwap()
                }
            }
        }

    private fun onCancelSwapClick(owner: () -> Boolean) =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheet.value !== owner || !owner()) return@launch
            isCancellingQuote = true
            quoteRequestJob?.cancel()
            cancelSwap.afterDismissal(owner) { dismissCancelSheet() }
        }

    private fun onDismissCancelClick(owner: () -> Boolean) =
        viewModelScope.launch {
            if (isCancellingQuote || cancelSheet.value !== owner) return@launch
            continueQuote(owner)
        }

    private suspend fun continueQuote(owner: () -> Boolean) {
        dismissCancelSheet()
        navigateToSwapQuoteIfAvailable(owner) {}
    }

    private suspend fun dismissCancelSheet() = cancelSheetDismissal.awaitHidden { cancelSheet.value = null }

    fun onCancelSheetHidden() = cancelSheetDismissal.onHidden()

    private fun canNavigateToReview() =
        !isCancellingQuote && cancelSheet.value == null && !cancelSheetDismissal.isPending

    private suspend fun awaitReviewNavigation(): Boolean {
        cancelSheetDismissal.awaitClosed()
        return canNavigateToReview()
    }

    private fun onSwapCurrencyTypeClick(newTextFieldAmount: BigDecimal?) {
        internalState.update {
            val isTokenInput = it.currencyType == CurrencyType.FIAT
            val decimals = if (it.swapDirection == SWAP_FROM_ZEC) ZEC_DECIMALS else it.swapAsset?.decimals
            val amount =
                if (isTokenInput && decimals != null) {
                    newTextFieldAmount?.setScale(decimals, RoundingMode.DOWN)?.stripTrailingZeros()
                } else {
                    newTextFieldAmount
                }
            val value =
                amount?.let { number ->
                    if (isTokenInput) {
                        stringResByNumber(number, minDecimals = 0, includeGroupingSeparator = false)
                    } else {
                        stringResByDynamicNumber(number, includeGroupingSeparator = false)
                    }
                } ?: stringRes("")
            it.copy(
                amountTextState =
                    NumberTextFieldInnerState(
                        innerTextFieldState = InnerTextFieldState(value = value, selection = TextSelection.End),
                        amount = amount,
                        lastValidAmount = amount
                    ),
                currencyType = if (isTokenInput) CurrencyType.TOKEN else CurrencyType.FIAT
            )
        }
    }

    private fun onTextFieldChange(new: NumberTextFieldInnerState) =
        internalState.update { it.copy(amountTextState = new) }

    /** Gates the quote request behind the refund warning, on the direction snapshotted at tap time. */
    private fun onRequestSwapQuoteClick(
        rendered: InternalStateImpl,
        amount: BigDecimal,
        fiatAmount: BigDecimal?,
        address: String,
    ) =
        viewModelScope.launch {
            val snapshot = currentQuoteDraft(rendered) ?: return@launch
            val asset = snapshot.swapAsset ?: return@launch
            if (asset.positiveUsdPrice == null || swapRepository.assets.value.zecAsset.positiveUsdPrice == null) {
                return@launch
            }
            val slippage = snapshot.slippage
            val direction = snapshot.swapDirection.coerceFor(isSelectedAccountSpendable.value)
            val contactNetwork = snapshot.selectedContact?.blockchain?.chainTicker
            val currentAddress = snapshot.selectedContact?.address ?: snapshot.addressText
            if (currentAddress != address || !validateSwapRecipient(
                    address, asset.chainTicker, asset.tokenTicker, contactNetwork,
                    allowPaymentAmount = false, tokenContract = asset.contractAddress
                ).isValid
            ) return@launch
            quoteActionInProgress = true
            try {
                if (!confirmQuoteDraft(rendered, fiatAmount, direction)) return@launch
                quoteRequestJob = coroutineContext.job
                internalState.update { it.copy(isRequestingQuote = true) }
                when (direction) {
                    SWAP_FROM_ZEC -> {
                        requestSwapQuote.requestExactInput(
                            amount = amount,
                            address = address,
                            selectedAsset = asset,
                            slippage = slippage,
                            canNavigateToSwapQuote = ::awaitReviewNavigation,
                            contactNetwork = contactNetwork
                        )
                    }

                    SWAP_INTO_ZEC -> {
                        requestSwapQuote.requestFlexInputIntoZec(
                            amount = amount,
                            refundAddress = address,
                            selectedAsset = asset,
                            slippage = slippage,
                            canNavigateToSwapQuote = ::awaitReviewNavigation,
                            contactNetwork = contactNetwork
                        )
                    }
                }
            } finally {
                quoteRequestJob = null
                quoteActionInProgress = false
                internalState.update { it.copy(isRequestingQuote = false) }
            }
        }

    private fun currentQuoteDraft(rendered: InternalStateImpl, checkAction: Boolean = true): InternalStateImpl? {
        val current = internalState.value
        if (current.isRequestingQuote || !canNavigateToReview()) return null
        val ownedAtRender = rendered.copy(account = current.account, swapAssets = current.swapAssets)
        val available = !checkAction || !quoteActionInProgress
        return current.takeIf { available && it == ownedAtRender && hasSameOriginPrice(rendered) }
    }

    private fun hasSameOriginPrice(rendered: InternalStateImpl): Boolean {
        val renderedZecPrice = rendered.swapAssets.zecAsset.positiveUsdPrice
        return rendered.swapDirection != SWAP_FROM_ZEC || renderedZecPrice == null ||
            renderedZecPrice == swapRepository.assets.value.zecAsset.positiveUsdPrice
    }

    private suspend fun confirmQuoteDraft(
        rendered: InternalStateImpl,
        fiatAmount: BigDecimal?,
        direction: SwapDirection,
    ): Boolean =
        navigateToSwapRefundWarning(fiatAmount = fiatAmount, mode = direction.toSwapMode()) &&
            currentQuoteDraft(rendered, checkAction = false) != null

    private fun onSwapInfoClick() {
        if (!quoteActionInProgress && canNavigateToReview()) navigateToSwapInfo()
    }

    private fun onAddressChange(new: String) =
        internalState.update { it.copy(selectedContact = null, addressText = new) }

    private fun onSwapAssetPickerClick() =
        viewModelScope.launch {
            val asset = navigateToSwapAssetPicker()
            if (asset != null) {
                internalState.update { it.selectAsset(asset) }
            }
        }

    private fun InternalStateImpl.selectAsset(asset: SwapAsset): InternalStateImpl =
        copy(
            selectedAssetId = asset.assetId,
            swapAsset = SwapPriceMarkets.findAsset(swapRepository.assets.value.data, asset.assetId)
        )
}

/**
 * A view-only account holds no spend authority ([isSpendable] false), so it may only swap into ZEC;
 * any other direction is reset to [SWAP_INTO_ZEC]. An account that has not loaded yet (null) keeps
 * the current direction.
 */
private fun SwapDirection.coerceFor(isSpendable: Boolean?): SwapDirection =
    if (isSpendable == false) SWAP_INTO_ZEC else this

internal enum class CurrencyType { TOKEN, FIAT }

internal interface InternalState {
    val account: WalletAccount?
    val swapAsset: SwapAsset?
    val currencyType: CurrencyType
    val amountTextState: NumberTextFieldInnerState
    val addressText: String
    val slippage: BigDecimal
    val swapAssets: SwapAssetsData
    val isRequestingQuote: Boolean
    val selectedContact: EnhancedABContact?
    val swapDirection: SwapDirection
    val isEphemeralAddressLocked: Boolean

    /**
     * Delegates to the shared [co.electriccoin.zcash.ui.common.model.canSpend] primitive — the same
     * one the pre-quote check in
     * [co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase.requestExactInput] validates
     * against. Re-evaluated whenever the selected account emits a new balance. Null while the
     * balance has not loaded yet.
     */
    fun canSpend(amount: Zatoshi): Boolean? = account.canSpend(amount)
}

internal data class InternalStateImpl(
    override val account: WalletAccount?,
    override val swapAsset: SwapAsset?,
    override val currencyType: CurrencyType,
    override val amountTextState: NumberTextFieldInnerState,
    override val addressText: String,
    override val slippage: BigDecimal,
    override val swapAssets: SwapAssetsData,
    override val isRequestingQuote: Boolean,
    override val selectedContact: EnhancedABContact?,
    override val swapDirection: SwapDirection,
    override val isEphemeralAddressLocked: Boolean,
    val selectedAssetId: String? = null,
) : InternalState
