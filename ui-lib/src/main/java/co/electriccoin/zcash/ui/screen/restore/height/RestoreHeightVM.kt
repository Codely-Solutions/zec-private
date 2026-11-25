package co.electriccoin.zcash.ui.screen.restore.height

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.component.NumberTextFieldState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.BlockHeightState
import co.electriccoin.zcash.ui.screen.common.birthdayHeightOrNull
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateArgs
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import co.electriccoin.zcash.ui.design.R as DesignR

class RestoreHeightVM(
    private val restoreHeight: RestoreHeight,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    private val blockHeightText = MutableStateFlow(NumberTextFieldInnerState())

    val state: StateFlow<BlockHeightState> =
        blockHeightText
            .map { text ->
                createState(text)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = createState(blockHeightText.value)
            )

    private fun createState(blockHeight: NumberTextFieldInnerState) =
        BlockHeightState(
            title = stringRes(R.string.root_existingWallet_restore),
            logo = null,
            onBack = ::onBack,
            dialogButton =
                IconButtonState(
                    icon = R.drawable.ic_help,
                    contentDescription = stringRes(DesignR.string.accessibility_wallet_birthday_help),
                    onClick = ::onInfoButtonClick,
                ),
            primaryButton =
                ButtonState(
                    stringRes(R.string.root_existingWallet_restore),
                    onClick = ::onRestoreClick,
                    isEnabled = blockHeight.birthdayHeightOrNull() != null,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ),
            primaryButtonTestTag = RestoreHeightTags.RESTORE_BTN,
            blockHeightFieldTestTag = RestoreHeightTags.BLOCK_HEIGHT_FIELD,
            secondaryButton =
                ButtonState(
                    stringRes(R.string.restoreWallet_birthday_estimate),
                    onClick = ::onEstimateClick
                ),
            blockHeight = NumberTextFieldState(innerState = blockHeight, onValueChange = ::onValueChanged)
        )

    private fun onEstimateClick() = navigationRouter.forward(RestoreDateArgs(sessionId = restoreHeight.sessionId))

    private fun onRestoreClick() {
        navigationRouter.forward(
            RestoreTorArgs(
                sessionId = restoreHeight.sessionId,
                blockHeight = blockHeightText.value.birthdayHeightOrNull()?.value ?: return
            )
        )
    }

    private fun onBack() = navigationRouter.back()

    private fun onInfoButtonClick() = navigationRouter.forward(SeedInfo)

    private fun onValueChanged(state: NumberTextFieldInnerState) = blockHeightText.update { state }
}
