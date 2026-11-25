package co.electriccoin.zcash.ui.screen.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationTargets
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToRequestZecUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveAccountIconUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.qrcode.ext.receiveAddress
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.DEFAULT
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.HW_WALLET
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressState.ColorMode.ZASHI
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Sapling
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Transparent
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType.Unified
import co.electriccoin.zcash.ui.screen.receive.info.ShieldedAddressInfoArgs
import co.electriccoin.zcash.ui.screen.receive.info.TransparentAddressInfoArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class ReceiveVM(
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val navigateToRequestZec: NavigateToRequestZecUseCase,
    private val viewOnlyContent: ViewOnlyContent,
    private val observeAccountIcon: ObserveAccountIconUseCase,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
) : ViewModel() {
    private val expandedIndex = MutableStateFlow(0)
    private val selectedAccount = observeSelectedWalletAccount().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val loading = ReceiveState(items = null, isLoading = true, onBack = ::onBack)

    internal val state =
        combine(
            expandedIndex,
            selectedAccount,
            observeAccountIcon.observeAll(),
            walletWriteAccess.version,
            appAccessSession.grant,
        ) { expandedIndex, account, colors, token, grant ->
            if (account == null || !appAccessSession.permits(grant) || !walletWriteAccess.permits(token)) {
                return@combine loading
            }
            val canAct = { type: ReceiveAddressType, address: String ->
                val current = selectedAccount.value
                appAccessSession.permits(grant) && walletWriteAccess.permits(token) &&
                    current?.sdkAccount?.accountUuid == account.sdkAccount.accountUuid &&
                    current.receiveAddress(type) == address
            }
            val icon =
                if (account is ZodlAccount) {
                    imageRes(R.drawable.ic_zec_round_full)
                } else {
                    observeAccountIcon.iconOf(colors, account)
                }
            ReceiveState(
                items =
                    listOfNotNull(
                        createAddressState(
                            account = account,
                            icon = icon,
                            address = account.unifiedAddress,
                            type = Unified,
                            isExpanded = expandedIndex == 0 || account.transparentAddress == null,
                            onClick = { onAddressClick(0) },
                            canAct = canAct,
                        ),
                        account.transparentAddress?.let { address -> createAddressState(
                            account = account,
                            icon = icon,
                            address = address,
                            type = Transparent,
                            isExpanded = expandedIndex == 1,
                            onClick = { onAddressClick(1) },
                            canAct = canAct,
                        ) },
                    ),
                isLoading = false,
                onBack = ::onBack
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = loading,
        )

    private fun onBack() = navigationRouter.back()

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun createAddressState(
        account: WalletAccount,
        icon: ImageResource,
        address: String,
        type: ReceiveAddressType,
        isExpanded: Boolean,
        onClick: () -> Unit,
        canAct: (ReceiveAddressType, String) -> Boolean,
    ) = ReceiveAddressState(
        icon = icon,
        title =
            when (account) {
                is KeystoneAccount -> {
                    if (type == Unified) {
                        stringRes(R.string.accounts_keystone_shieldedAddress)
                    } else {
                        stringRes(R.string.accounts_keystone_transparentAddress)
                    }
                }

                is LedgerAccount -> {
                    if (type == Unified) {
                        stringRes(R.string.accounts_ledger_shieldedAddress)
                    } else {
                        stringRes(R.string.accounts_ledger_transparentAddress)
                    }
                }

                is ViewOnlyAccount -> {
                    if (type == Unified) {
                        viewOnlyContent.receiveShieldedTitle
                    } else {
                        viewOnlyContent.receiveTransparentTitle
                    }
                }

                is ZodlAccount -> {
                    if (type == Unified) {
                        stringRes(R.string.accounts_zashi_shieldedAddress)
                    } else {
                        stringRes(R.string.accounts_zashi_transparentAddress)
                    }
                }
            },
        subtitle = stringResByAddress(value = address),
        isShielded = type == Unified,
        onCopyClicked = {
            if (canAct(type, address)) copyToClipboard(value = address)
        },
        onQrClicked = { if (canAct(type, address)) onQrCodeClick(type) },
        onRequestClicked = { if (canAct(type, address)) onRequestClick(type) },
        onClick = onClick,
        isExpanded = isExpanded,
        colorMode =
            when (account) {
                is KeystoneAccount,
                is LedgerAccount,
                is ViewOnlyAccount -> if (type == Unified) HW_WALLET else DEFAULT

                is ZodlAccount -> if (type == Unified) ZASHI else DEFAULT
            },
        infoIconButton =
            IconButtonState(
                when (type) {
                    Sapling,
                    Unified -> {
                        when (account) {
                            is KeystoneAccount -> R.drawable.ic_receive_ks_shielded_info
                            is LedgerAccount -> R.drawable.ic_receive_ks_shielded_info
                            is ViewOnlyAccount -> R.drawable.ic_receive_ks_shielded_info
                            is ZodlAccount -> R.drawable.ic_receive_zashi_shielded_info
                        }
                    }

                    Transparent -> {
                        R.drawable.ic_receive_zcash_info
                    }
                },
                contentDescription = stringRes(
                    when (type) {
                        Sapling, Unified -> co.electriccoin.zcash.ui.design.R.string.receive_help_shielded_title
                        Transparent -> co.electriccoin.zcash.ui.design.R.string.receive_help_transparent_title
                    },
                ),
                onClick = { if (canAct(type, address)) onAddressInfoClick(type) }
            )
    )

    private fun onRequestClick(addressType: ReceiveAddressType) = navigateToRequestZec(addressType.ordinal)

    private fun onQrCodeClick(addressType: ReceiveAddressType) =
        navigationRouter.forward("${NavigationTargets.QR_CODE}/${addressType.ordinal}")

    private fun onAddressClick(index: Int) {
        expandedIndex.update { index }
    }

    private fun onAddressInfoClick(type: ReceiveAddressType) {
        when (type) {
            Sapling,
            Unified -> navigationRouter.forward(ShieldedAddressInfoArgs)

            Transparent -> navigationRouter.forward(TransparentAddressInfoArgs)
        }
    }
}
