package co.electriccoin.zcash.ui.screen.qrcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.util.Ellipsize
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.qrcode.ext.fromReceiveAddressType
import co.electriccoin.zcash.ui.screen.qrcode.ext.receiveAddress
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QrCodeVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val addressTypeOrdinal: Int,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val shareQR: ShareQRUseCase,
    private val viewOnlyContent: ViewOnlyContent,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val loading = QrCodeState.Loading(::onBack)

    private val selectedAccount =
        observeSelectedWalletAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val state =
        combine(selectedAccount, appAccessSession.grant, walletWriteAccess.version) { account, grant, token ->
                val addressType = ReceiveAddressType.fromOrdinal(addressTypeOrdinal)
                val walletAddress = account?.fromReceiveAddressType(addressType)
                val hasAccess = appAccessSession.permits(grant) && walletWriteAccess.permits(token)

                if (account == null || walletAddress == null || !hasAccess) {
                    loading
                } else {
                    val permitted = { address: String ->
                        val current = selectedAccount.value
                        appAccessSession.permits(grant) && walletWriteAccess.permits(token) &&
                            current?.sdkAccount?.accountUuid == account.sdkAccount.accountUuid &&
                            current.receiveAddress(addressType) == address && address == walletAddress.address
                    }
                    QrCodeState.Prepared(
                        walletAddress = walletAddress,
                        formatterAddress =
                            stringResByAddress(
                                walletAddress.address,
                                if (walletAddress is WalletAddress.Transparent) Ellipsize.NONE else Ellipsize.MIDDLE
                            ),
                        onAddressCopy = { address -> if (permitted(address)) copyToClipboard(address) },
                        onQrCodeShare = { address ->
                            viewModelScope.launch {
                                if (!permitted(address)) return@launch
                                shareQR(
                                    qrData = address,
                                    shareText = stringRes(R.string.qr_code_share_chooser_text, CURRENCY_TICKER),
                                    sharePickerText = stringRes(R.string.qr_code_share_chooser_title, CURRENCY_TICKER),
                                    filenamePrefix = "zcash_address_qr_",
                                    centerIcon =
                                        if (walletAddress is WalletAddress.Transparent) {
                                            R.drawable.ic_zec_qr_transparent
                                        } else {
                                            R.drawable.ic_zec_qr_shielded
                                        },
                                    isAllowed = {
                                        navigationRouter.isForegroundAtEntry(sourceEntryId) &&
                                            permitted(address)
                                    },
                                )
                            }
                        },
                        onBack = ::onBack,
                        title = addressTitle(account, walletAddress)
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = loading
            )

    private fun onBack() = navigationRouter.back()

    private fun addressTitle(account: WalletAccount, walletAddress: WalletAddress): StringResource =
        when (walletAddress) {
            is WalletAddress.Unified -> {
                when (account) {
                    is KeystoneAccount -> stringRes(R.string.qr_code_wallet_address_shielded_keystone)
                    is LedgerAccount -> stringRes(R.string.accounts_ledger_shieldedAddress)
                    is ViewOnlyAccount -> viewOnlyContent.receiveShieldedTitle
                    is ZodlAccount -> stringRes(R.string.accounts_zashi_shieldedAddress)
                }
            }

            is WalletAddress.Sapling -> {
                when (account) {
                    is KeystoneAccount -> stringRes(R.string.qr_code_wallet_address_sapling_keystone)

                    is LedgerAccount,
                    is ViewOnlyAccount,
                    is ZodlAccount -> stringRes(R.string.receive_saplingAddress)
                }
            }

            is WalletAddress.Transparent -> {
                when (account) {
                    is ViewOnlyAccount -> viewOnlyContent.receiveTransparentTitle

                    is KeystoneAccount,
                    is LedgerAccount,
                    is ZodlAccount -> stringRes(R.string.accounts_zashi_transparentAddress)
                }
            }

            else -> {
                error("Unsupported address type: $walletAddress")
            }
        }

}
