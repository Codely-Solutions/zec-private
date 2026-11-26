package co.electriccoin.zcash.ui.screen.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import org.koin.compose.koinInject
import co.electriccoin.zcash.ui.design.R as DesignR

@Composable
internal fun HomeSeedMismatchNotice(
    mismatch: Boolean,
    isStillMismatched: () -> Boolean,
    canDismiss: Boolean,
) {
    val auth = koinActivityViewModel<AuthenticationViewModel>()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val support = koinInject<SendEmailUseCase>()
    val authentication by auth.appAccessAuthenticationResultState.collectAsStateWithLifecycle()
    val welcome by auth.showWelcomeAnimation.collectAsStateWithLifecycle()
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    SeedMismatchNotice(
        mismatch = mismatch,
        authorized = allowMismatchNotice(authentication, welcome, lifecycleState),
        canDismiss = canDismiss,
        onSupport = {
            support.sendBlankSupport {
                isStillMismatched() && allowMismatchNotice(
                    auth.appAccessAuthenticationResultState.value,
                    auth.showWelcomeAnimation.value,
                    lifecycle.currentState,
                )
            }
        },
    )
}

internal fun allowMismatchNotice(
    authentication: AuthenticationUIState,
    welcome: Boolean,
    lifecycle: Lifecycle.State,
): Boolean =
    !welcome && lifecycle == Lifecycle.State.RESUMED &&
        (authentication == AuthenticationUIState.Successful || authentication == AuthenticationUIState.NotRequired)

@Composable
internal fun SeedMismatchNotice(
    mismatch: Boolean,
    authorized: Boolean,
    canDismiss: Boolean,
    onSupport: () -> Unit,
) {
    var dismissed by remember(mismatch) { mutableStateOf(false) }
    if (mismatch && authorized && !dismissed) {
        val dismiss = if (canDismiss) ({ dismissed = true }) else null
        AppAlertDialog(
            onDismissRequest = dismiss,
            title = stringResource(R.string.private_wallet_local_data_unavailable),
            text = { Text(stringResource(R.string.private_wallet_reopen_for_data)) },
            confirmButtonText = stringResource(R.string.private_wallet_contact_support),
            onConfirmButtonClick = onSupport,
            dismissButtonText = if (canDismiss) stringResource(DesignR.string.general_close) else null,
            onDismissButtonClick = dismiss,
        )
    }
}
