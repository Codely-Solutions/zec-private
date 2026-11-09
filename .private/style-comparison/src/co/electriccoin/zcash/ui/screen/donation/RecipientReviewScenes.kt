package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.model.SwapDirection
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.pay.ExactOutputStateCallbacks
import co.electriccoin.zcash.ui.screen.pay.ExactOutputVMMapper
import co.electriccoin.zcash.ui.screen.pay.PayView
import co.electriccoin.zcash.ui.screen.swap.CurrencyType
import co.electriccoin.zcash.ui.screen.swap.SwapStateCallbacks
import co.electriccoin.zcash.ui.screen.swap.SwapVMMapper
import co.electriccoin.zcash.ui.screen.swap.SwapView
import java.math.BigDecimal
import kotlin.time.Instant
import co.electriccoin.zcash.ui.screen.pay.InternalStateImpl as PayInternalState
import co.electriccoin.zcash.ui.screen.swap.InternalStateImpl as SwapInternalState

/** Production forms and mappers with public-format synthetic values. No request callback performs work.
 * Kept compatible with the pre-sprint mapper/state API for identical before/after capture inputs.
 */
@Composable
internal fun RecipientReviewScene(
    screen: String,
    appBar: ZashiMainTopAppBarState,
    account: WalletAccount,
    balance: BalanceWidgetState,
): Boolean {
    if (screen !in setOf(
            "recipient-swap-mismatch", "recipient-swap-corrected", "recipient-pay-mismatch",
            "recipient-pay-corrected", "recipient-qr-mismatch", "recipient-swap-empty"
        )) return false
    val btcIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc)
    val btcChain = SwapBlockchain("btc", stringRes("Bitcoin"), btcIcon)
    val ethChain = SwapBlockchain(
        "eth", stringRes("Ethereum"), imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_chain_eth)
    )
    val btc = NearSwapAsset(
        tokenTicker = "BTC", tokenName = stringRes("Bitcoin"), tokenIcon = btcIcon,
        usdPrice = BigDecimal("100000"), assetId = "synthetic-btc", decimals = 8, blockchain = btcChain,
    )
    val wbtc = btc.copy(
        tokenTicker = "WBTC", tokenName = stringRes("Wrapped Bitcoin"), assetId = "synthetic-wbtc",
        blockchain = ethChain, tokenIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_wbtc),
    )
    val zecIcon = imageRes(R.drawable.ic_zec_round_full)
    val zec = NearSwapAsset(
        tokenTicker = "ZEC", tokenName = stringRes("Zcash"), tokenIcon = zecIcon,
        usdPrice = BigDecimal("40"), assetId = "synthetic-zec", decimals = 8,
        blockchain = SwapBlockchain("zec", stringRes("Zcash"), zecIcon),
    )
    // Repeated-byte address is a syntax fixture; the host never requests or funds it.
    val address = "0x1111111111111111111111111111111111111111"
    val empty = screen == "recipient-swap-empty"
    val qr = screen == "recipient-qr-mismatch"
    val corrected = screen.endsWith("corrected")
    val selected = if (corrected) wbtc else btc
    val contact = if (qr || empty) null else EnhancedABContact(
        AddressBookContact("Synthetic WBTC contact", address, Instant.fromEpochMilliseconds(0), "eth"),
        ethChain,
    )
    val assets = SwapAssetsData(data = listOf(btc, wbtc), zecAsset = zec)
    val recipient = if (empty) "" else if (qr) "ethereum:$address@1" else address
    if (screen.startsWith("recipient-pay")) {
        val state = PayInternalState(
            address = recipient, isABHintVisible = false, selectedABContact = contact, asset = selected,
            amount = NumberTextFieldInnerState.fromAmount(BigDecimal("0.00001")),
            fiatAmount = NumberTextFieldInnerState.fromAmount(BigDecimal.ONE), slippage = BigDecimal("2"),
            isRequestingQuote = false, account = account, swapAssets = assets, isEphemeralAddressLocked = false,
        )
        PayView(
            ExactOutputVMMapper().createState(state, ExactOutputStateCallbacks(
                onBack = {}, onSwapInfoClick = {}, onSwapAssetPickerClick = {}, onSlippageClick = {},
                onRequestSwapQuoteClick = { _, _, _ -> }, onTryAgainClick = {}, onAddressChange = {},
                onTextFieldChange = { _, _ -> }, onQrCodeScannerClick = {}, onAddressBookClick = {},
                onDeleteSelectedContactClick = {},
            )),
            balanceState = balance, appBarState = appBar,
        )
    } else {
        val state = SwapInternalState(
            account = account, swapAsset = selected, currencyType = CurrencyType.TOKEN,
            amountTextState = NumberTextFieldInnerState.fromAmount(BigDecimal("0.01")),
            addressText = recipient, slippage = BigDecimal("2"), swapAssets = assets,
            isRequestingQuote = false, selectedContact = contact, swapDirection = SwapDirection.SWAP_FROM_ZEC,
            isEphemeralAddressLocked = false,
        )
        SwapView(
            SwapVMMapper().createState(state, SwapStateCallbacks(
                onBack = {}, onSwapInfoClick = {}, onSwapAssetPickerClick = {}, onSwapCurrencyTypeClick = {},
                onSlippageClick = {}, onRequestSwapQuoteClick = { _, _, _ -> }, onTryAgainClick = {},
                onAddressChange = {}, onTextFieldChange = {}, onQrCodeScannerClick = {}, onAddressBookClick = {},
                onDeleteSelectedContactClick = {}, onBalanceButtonClick = {}, onChangeButtonClick = {}, onAddressClick = {},
            )), appBarState = appBar,
        )
    }
    return true
}
