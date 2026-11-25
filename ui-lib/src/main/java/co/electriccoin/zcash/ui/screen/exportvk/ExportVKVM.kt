package co.electriccoin.zcash.ui.screen.exportvk

import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.GetVKUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveAccountIconUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StyledStringStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.exportvk.confirm.ExportVKConfirmArgs
import co.electriccoin.zcash.ui.screen.exportvk.detail.VKDetailArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal class ExportVKVM(
    private val getVK: GetVKUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val observeAccountIcon: ObserveAccountIconUseCase,
) : ViewModel() {
    private val selected = MutableStateFlow<VKType?>(null)

    val state =
        combine(
            selected,
            observeSelectedWalletAccount.require(),
            observeAccountIcon.observeAll(),
            getVK.walletVersion,
        ) { selected, account, colors, generation ->
            createState(selected, account, observeAccountIcon.iconOf(colors, account), generation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    private fun createState(
        selected: VKType?,
        account: WalletAccount,
        logo: ImageResource,
        generation: Long,
    ) = ExportVKState(
        logo = logo,
        description =
            styledStringResource(
                R.string.exportViewingKey_description,
                account.name withStyle StyledStringStyle(fontWeight = FontWeight.Bold)
            ),
        options =
            VKType.entries.map { type ->
                VKOptionState(
                    type = type,
                    title = stringRes(type.titleRes),
                    subtitle = stringRes(type.subtitleRes),
                    isChecked = type == selected,
                    onClick = { onOptionClick(type) }
                )
            },
        continueButton =
            ButtonState(
                text = stringRes(R.string.exportViewingKey_continue),
                isEnabled = selected != null && getVK.availability(account)[selected] == true,
                onClick = { if (selected != null) onContinueClick(selected, account, generation) }
            ),
        onBack = ::onBack
    )

    private fun onOptionClick(type: VKType) = selected.update { type }

    private fun onContinueClick(type: VKType, account: WalletAccount, generation: Long) {
        if (selected.value != type) return
        val id = getVK.startReview(account, generation, type) ?: return
        when (type) {
            VKType.INCOMING -> navigationRouter.replace(VKDetailArgs(VKType.INCOMING, id))
            VKType.FULL -> navigationRouter.forward(ExportVKConfirmArgs(id))
        }
    }

    private fun onBack() = navigationRouter.back()
}

private val VKType.titleRes: Int
    get() =
        when (this) {
            VKType.INCOMING -> R.string.exportViewingKey_option_incoming
            VKType.FULL -> R.string.exportViewingKey_option_full
        }

private val VKType.subtitleRes: Int
    get() =
        when (this) {
            VKType.INCOMING -> R.string.exportViewingKey_option_incoming_desc
            VKType.FULL -> R.string.exportViewingKey_option_full_desc
        }
