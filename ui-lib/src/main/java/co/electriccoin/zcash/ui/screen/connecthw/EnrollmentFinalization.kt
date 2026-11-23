package co.electriccoin.zcash.ui.screen.connecthw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.MutableLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.usecase.CreateHWWalletAccountUseCase
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.VerticalSpacer
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal fun ViewModel.observeEnrollmentFinalization(
    enrollment: HWWalletEnrollment,
    createAccount: CreateHWWalletAccountUseCase,
    lce: MutableLce<Unit>,
) = combine(createAccount.observeCommittedBirthday(enrollment), lce.state) { birthday, operation ->
    birthday?.let { committed ->
        EnrollmentFinalizationState(
            birthday = committed,
            finish = ButtonState(
                text = stringRes(R.string.enrollment_finish_setup),
                isEnabled = !operation.loading,
                isLoading = operation.loading,
                onClick = {
                    lce.guardLoading {
                        lce.execute { createAccount.finish(enrollment, committed) }
                    }
                },
            ),
        )
    }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

@Composable
internal fun EnrollmentFinalizationView(state: EnrollmentFinalizationState, onBack: () -> Unit) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(navigationAction = { ZashiTopAppBarBackNavigation(onBack = onBack) })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).scaffoldPadding(padding),
        ) {
            Text(
                text = stringRes(R.string.enrollment_finish_setup).getValue(),
                style = ZashiTypography.header6,
                color = ZashiColors.Text.textPrimary,
            )
            VerticalSpacer(8.dp)
            Text(
                text = state.birthday.message().getValue(),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textPrimary,
            )
            VerticalSpacer(24.dp)
            ZashiButton(state = state.finish, modifier = Modifier.fillMaxWidth())
        }
    }
}
