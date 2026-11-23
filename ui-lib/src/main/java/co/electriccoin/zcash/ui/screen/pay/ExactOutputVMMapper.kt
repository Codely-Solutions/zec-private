package co.electriccoin.zcash.ui.screen.pay

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.errorText
import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.positiveUsdPrice
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.near.toExactNearQuoteAmountOrNull
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.component.AssetCardState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.component.TextFieldState
import co.electriccoin.zcash.ui.design.component.TextSelection
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StringResourceColor
import co.electriccoin.zcash.ui.design.util.StyledStringResource
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.swap.SwapErrorFooterState
import co.electriccoin.zcash.ui.screen.swap.convertZecToZatoshi
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.isServiceUnavailable
import io.ktor.client.plugins.ResponseException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

@Suppress("TooManyFunctions")
internal class ExactOutputVMMapper {
    fun createState(
        internalState: InternalState,
        callbacks: ExactOutputStateCallbacks,
    ): PayState {
        val state = ExactOutputInternalState(internalState)
        val amountState = createAmountState(state, callbacks.onTokenAmountChange)
        return PayState(
            info =
                IconButtonState(
                    icon = co.electriccoin.zcash.ui.design.R.drawable.ic_info,
                    onClick = callbacks.onSwapInfoClick
                ),
            address = createAddressState(state, callbacks.onAddressChange),
            asset = createAssetState(state, callbacks.onSwapAssetPickerClick),
            abContact = createAddressContactState(state, callbacks.onDeleteSelectedContactClick),
            abButton =
                IconButtonState(
                    icon = R.drawable.send_address_book,
                    contentDescription = stringRes(R.string.send_address_book_content_description),
                    onClick = callbacks.onAddressBookClick,
                    isEnabled = !state.isRequestingQuote
                ),
            qrButton =
                IconButtonState(
                    icon = R.drawable.qr_code_icon,
                    contentDescription = stringRes(R.string.send_scan_content_description),
                    onClick = callbacks.onQrCodeScannerClick,
                    isEnabled = !state.isRequestingQuote
                ),
            amount = amountState,
            amountFiat = createFiatAmountState(state, callbacks.onFiatAmountChange),
            amountError = createAmountErrorState(state),
            zecAmount = createZecAmount(state),
            slippage = createSlippageState(state, callbacks.onSlippageClick),
            errorFooter = createErrorFooterState(state),
            primaryButton =
                createPrimaryButtonState(
                    textField = amountState,
                    state = state,
                    onRequestSwapQuoteClick = callbacks.onRequestSwapQuoteClick,
                    onTryAgainClick = callbacks.onTryAgainClick
                ),
            isABHintVisible = state.isABHintVisible,
            onBack = callbacks.onBack,
            addressPlaceholder =
                state.asset
                    ?.let {
                        stringRes(
                            co.electriccoin.zcash.ui.design.R.string.general_enter_address_partial,
                            it.chainName
                        )
                    } ?: stringRes(co.electriccoin.zcash.ui.design.R.string.general_enter_address),
            infoFooter =
                if (state.isEphemeralAddressLocked) {
                    stringRes(
                        "Renewing temporary addresses. Once the reset transaction is processed, you will be able to " +
                            "proceed. This may take up to 2 minutes."
                    )
                } else {
                    null
                }
        )
    }

    private fun createZecAmount(state: ExactOutputInternalState): StyledStringResource =
        stringResByDynamicCurrencyNumber(state.getZec() ?: BigDecimal(0), CURRENCY_TICKER).withStyle(
            StyledStringStyle(
                color =
                    if (state.isInsufficientFunds) {
                        StringResourceColor.HINT_ERROR
                    } else {
                        StringResourceColor.PRIMARY
                    }
            )
        )

    fun createFiatAmountInnerState(
        amountInnerState: NumberTextFieldInnerState,
        fiatInnerState: NumberTextFieldInnerState,
        asset: SwapAsset?
    ): NumberTextFieldInnerState {
        val amount = amountInnerState.amount
        val fiat =
            asset.positiveUsdPrice?.let { amount?.multiply(it, MathContext.DECIMAL128) }
        return fiatInnerState.copy(
            innerTextFieldState =
                fiatInnerState.innerTextFieldState.copy(
                    value = fiat?.let { stringResByDynamicNumber(it, includeGroupingSeparator = false) } ?: stringRes(""),
                    selection = TextSelection.End
                ),
            amount = fiat,
            lastValidAmount = fiat ?: fiatInnerState.lastValidAmount
        )
    }

    /** Converts fiat into whole base units at the current token price. */
    fun createAmountInnerState(
        fiatInnerState: NumberTextFieldInnerState,
        amountInnerState: NumberTextFieldInnerState,
        asset: SwapAsset?
    ): NumberTextFieldInnerState {
        val fiat = fiatInnerState.amount
        val amount =
            asset?.let { selected ->
                selected.positiveUsdPrice?.let { fiat?.divide(it, MathContext.DECIMAL128) }
                    ?.setScale(selected.decimals, RoundingMode.DOWN)
            }
        return amountInnerState.copy(
            innerTextFieldState =
                amountInnerState.innerTextFieldState.copy(
                    value =
                        amount
                            ?.let { stringResByNumber(it, minDecimals = 0, includeGroupingSeparator = false) }
                            ?: stringRes(""),
                    selection = TextSelection.End
                ),
            amount = amount,
            lastValidAmount = amount ?: amountInnerState.lastValidAmount
        )
    }

    private fun createAmountErrorState(state: ExactOutputInternalState): StringResource? =
        when {
            state.hasUnrepresentableTokenAmount -> {
                stringRes(co.electriccoin.zcash.ui.design.R.string.send_error_invalidAmount)
            }
            state.isInsufficientFunds -> stringRes(R.string.send_error_insufficientFunds)
            else -> null
        }

    private fun createAmountState(
        state: ExactOutputInternalState,
        onTextFieldChange: (NumberTextFieldInnerState) -> Unit,
    ): NumberTextFieldState =
        NumberTextFieldState(
            innerState = state.amount,
            onValueChange = onTextFieldChange,
            isEnabled = !state.isRequestingQuote,
            explicitError =
                if (state.hasUnrepresentableTokenAmount || state.isInsufficientFunds) {
                    stringRes("")
                } else {
                    null
                }
        )

    private fun createFiatAmountState(
        state: ExactOutputInternalState,
        onTextFieldChange: (NumberTextFieldInnerState) -> Unit,
    ): NumberTextFieldState =
        NumberTextFieldState(
            innerState = state.fiatAmount,
            onValueChange = onTextFieldChange,
            isEnabled = !state.isRequestingQuote,
            explicitError =
                if (state.isInsufficientFunds) {
                    stringRes("")
                } else {
                    null
                }
        )

    private fun createAssetState(
        state: ExactOutputInternalState,
        onSwapAssetPickerClick: () -> Unit
    ): AssetCardState =
        if (state.asset == null) {
            AssetCardState.Loading(
                onClick = onSwapAssetPickerClick,
                isEnabled = !state.isRequestingQuote,
            )
        } else {
            AssetCardState.Data(
                token = state.asset.tokenTicker.let { stringRes(it) },
                chain = state.asset.chainName,
                isSingleLine = true,
                bigIcon = state.asset.tokenIcon,
                smallIcon = state.asset.chainIcon,
                onClick = onSwapAssetPickerClick,
                isEnabled = !state.isRequestingQuote,
            )
        }

    private fun createAddressContactState(
        state: ExactOutputInternalState,
        onDeleteSelectedContactClick: () -> Unit
    ): ChipButtonState? {
        if (state.selectedABContact == null) return null

        return ChipButtonState(
            text = stringRes(state.selectedABContact.contact.name),
            onClick = onDeleteSelectedContactClick,
            endIcon = co.electriccoin.zcash.ui.design.R.drawable.ic_chip_close,
            isEnabled = !state.isRequestingQuote,
        )
    }

    private fun createSlippageState(
        state: ExactOutputInternalState,
        onSlippageClick: (BigDecimal?) -> Unit
    ): ButtonState {
        val amount = state.slippage
        return ButtonState(
            text = stringResByNumber(amount, minDecimals = 0) + stringRes("%"),
            icon = R.drawable.ic_swap_slippage,
            onClick = { onSlippageClick(state.getOriginFiatAmount()) },
            isEnabled = !state.isRequestingQuote,
        )
    }

    private fun createErrorFooterState(state: ExactOutputInternalState): SwapErrorFooterState? {
        if (state.swapAssets.error == null) return null

        val isServiceUnavailableError =
            state.swapAssets.error is ResponseException &&
                state.swapAssets.error.response.status
                    .isServiceUnavailable()

        return SwapErrorFooterState(
            title =
                if (isServiceUnavailableError) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.swapAndPay_failure_laterTitle)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.swapAndPay_failure_retryTitle)
                },
            subtitle =
                if (isServiceUnavailableError) {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.swapAndPay_failure_laterDesc)
                } else {
                    stringRes(co.electriccoin.zcash.ui.design.R.string.swapAndPay_failure_retryDesc)
                }
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun createPrimaryButtonState(
        textField: NumberTextFieldState,
        state: ExactOutputInternalState,
        onRequestSwapQuoteClick: (amount: BigDecimal, fiatAmount: BigDecimal?, address: String) -> Unit,
        onTryAgainClick: () -> Unit
    ): ButtonState? {
        if (state.swapAssets.error is ResponseException &&
            state.swapAssets.error.response.status
                .isServiceUnavailable()
        ) {
            return null
        }

        val amount = textField.innerState.amount
        val shouldRetryPrices = state.swapAssets.shouldRetryPrices(state.asset)
        val isWaitingForPrices =
            state.swapAssets.isLoading &&
                (state.asset.positiveUsdPrice == null || state.swapAssets.zecAsset.positiveUsdPrice == null)
        return ButtonState(
            text =
                when {
                    state.isEphemeralAddressLocked -> {
                        stringRes(co.electriccoin.zcash.ui.design.R.string.swapAndPay_status_processing)
                    }

                    shouldRetryPrices -> {
                        stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain)
                    }

                    isWaitingForPrices -> {
                        stringRes(co.electriccoin.zcash.ui.design.R.string.general_loading)
                    }

                    else -> {
                        stringRes(co.electriccoin.zcash.ui.design.R.string.send_review)
                    }
                },
            style = if (shouldRetryPrices) ButtonStyle.DESTRUCTIVE1 else null,
            onClick = {
                if (shouldRetryPrices) {
                    onTryAgainClick()
                } else {
                    val address = state.selectedABContact?.address ?: state.address
                    state.getOriginTokenAmount()?.takeUnless { state.hasUnrepresentableTokenAmount }?.let {
                        onRequestSwapQuoteClick(it, state.getOriginFiatAmount(), address)
                    }
                }
            },
            isEnabled =
                when {
                    state.isEphemeralAddressLocked -> {
                        false
                    }

                    shouldRetryPrices -> {
                        !state.swapAssets.isLoading || state.swapAssets.data != null
                    }

                    else -> {
                        state.swapAssets.data != null &&
                            state.asset != null &&
                            state.asset.positiveUsdPrice != null &&
                            state.swapAssets.zecAsset.positiveUsdPrice != null &&
                            !textField.isError &&
                            amount != null &&
                            amount > BigDecimal(0) &&
                            (state.address.isNotBlank() || state.selectedABContact != null) &&
                            validateSwapRecipient(
                                state.selectedABContact?.address ?: state.address,
                                state.asset.chainTicker,
                                state.asset.tokenTicker,
                                state.selectedABContact?.blockchain?.chainTicker,
                                tokenContract = state.asset.contractAddress,
                            ).isValid &&
                            !state.isRequestingQuote
                    }
                },
            isLoading =
                state.isEphemeralAddressLocked ||
                    state.isRequestingQuote ||
                    isWaitingForPrices,
        )
    }

    private fun createAddressState(
        state: ExactOutputInternalState,
        onAddressChange: (String) -> Unit
    ): TextFieldState {
        val text = state.address
        return TextFieldState(
            error =
                validateSwapRecipient(
                    state.selectedABContact?.address ?: text,
                    state.asset?.chainTicker,
                    state.asset?.tokenTicker,
                    state.selectedABContact?.blockchain?.chainTicker,
                    tokenContract = state.asset?.contractAddress,
                ).errorText(state.asset?.chainTicker),
            value = stringRes(text),
            onValueChange = onAddressChange,
            isEnabled = !state.isRequestingQuote,
        )
    }
}

private data class ExactOutputInternalState(
    override val address: String,
    override val selectedABContact: EnhancedABContact?,
    override val asset: SwapAsset?,
    override val amount: NumberTextFieldInnerState,
    override val fiatAmount: NumberTextFieldInnerState,
    override val slippage: BigDecimal,
    override val isRequestingQuote: Boolean,
    override val account: WalletAccount?,
    override val swapAssets: SwapAssetsData,
    override val isABHintVisible: Boolean,
    override val isEphemeralAddressLocked: Boolean
) : InternalState {
    constructor(original: InternalState) : this(
        address = original.address,
        selectedABContact = original.selectedABContact,
        asset = original.asset,
        amount = original.amount,
        fiatAmount = original.fiatAmount,
        slippage = original.slippage,
        isRequestingQuote = original.isRequestingQuote,
        account = original.account,
        swapAssets = original.swapAssets,
        isABHintVisible = original.isABHintVisible,
        isEphemeralAddressLocked = original.isEphemeralAddressLocked
    )

    /**
     * The one insufficient-funds signal every facet of this screen (ZEC amount color, error label,
     * both text fields) renders from, computed once per state instance so the rule can never drift
     * between them.
     */
    val isInsufficientFunds: Boolean = getZatoshi()?.let { canSpend(it) == false } == true

    val hasUnrepresentableTokenAmount: Boolean =
        amount.amount?.let { value ->
            asset != null && value.signum() > 0 && value.toExactNearQuoteAmountOrNull(asset.decimals) == null
        } == true

    fun getOriginFiatAmount(): BigDecimal? {
        val tokenAmount = amount.amount
        val usdPrice = asset.positiveUsdPrice
        return if (tokenAmount == null || usdPrice == null) {
            null
        } else {
            tokenAmount.multiply(usdPrice, MathContext.DECIMAL128)
        }
    }

    fun getOriginTokenAmount(): BigDecimal? = amount.amount

    fun getZatoshi(): Zatoshi? = getZec()?.convertZecToZatoshi()

    fun getZec(): BigDecimal? {
        val amount = getOriginFiatAmount()
        val zecPrice = swapAssets.zecAsset.positiveUsdPrice
        return if (amount == null || zecPrice == null) null else amount.divide(zecPrice, MathContext.DECIMAL128)
    }
}

/**
 * The [PayVM] callbacks wired into [PayState]. Bundled into a named type so
 * [ExactOutputVMMapper.createState] stays a 2-arg call — adding/reordering a callback can't silently
 * misroute the others (the VM tests capture this object and match callbacks by name, not position).
 */
internal data class ExactOutputStateCallbacks(
    val onBack: () -> Unit,
    val onSwapInfoClick: () -> Unit,
    val onSwapAssetPickerClick: () -> Unit,
    val onSlippageClick: (BigDecimal?) -> Unit,
    val onRequestSwapQuoteClick: (amount: BigDecimal, fiatAmount: BigDecimal?, address: String) -> Unit,
    val onTryAgainClick: () -> Unit,
    val onAddressChange: (String) -> Unit,
    val onTokenAmountChange: (NumberTextFieldInnerState) -> Unit,
    val onFiatAmountChange: (NumberTextFieldInnerState) -> Unit,
    val onQrCodeScannerClick: () -> Unit,
    val onAddressBookClick: () -> Unit,
    val onDeleteSelectedContactClick: () -> Unit,
)
