package co.electriccoin.zcash.ui.screen.error

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.DialogState
import co.electriccoin.zcash.ui.design.component.ZashiScreenDialog
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import org.koin.compose.koinInject

@Composable
fun DialogView(state: ErrorState?, bodyAction: @Composable (() -> Unit)? = null) {
    ZashiScreenDialog(
        state =
            state?.let {
                DialogState(
                    title = it.title,
                    message = it.message,
                    positive = it.positive,
                    negative = it.negative,
                    onDismissRequest = it.onBack
                )
            },
        bodyAction = bodyAction,
    )
}

@Composable
internal fun UnavailableErrorView() {
    val router = koinInject<NavigationRouter>()
    DialogView(
        ErrorState(
            title = stringRes(R.string.private_error_unavailable_title),
            message = stringRes(R.string.private_error_unavailable_message),
            positive = ButtonState(text = stringRes(R.string.general_close), onClick = router::back),
            negative = null,
            onBack = router::back,
        )
    )
}

@Composable
internal fun StoredWalletUnavailableView(
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    onBack: () -> Unit,
    isRetrying: Boolean = false,
    onReset: () -> Unit,
) {
    DialogView(
        ErrorState(
            title = stringRes(R.string.wallet_store_unavailable_title),
            message = stringRes(R.string.wallet_store_unavailable_message),
            positive = ButtonState(
                text = stringRes(R.string.wallet_initialization_retry),
                onClick = onRetry,
                isEnabled = !isRetrying,
                isLoading = isRetrying,
            ),
            negative = ButtonState(text = stringRes(R.string.support_header), onClick = onSupport),
            onBack = onBack,
        )
    ) {
        TextButton(onClick = onReset, enabled = !isRetrying) {
            Text(stringResource(R.string.deleteWallet_title))
        }
    }
}

@PreviewScreens
@Composable
private fun Preview() =
    ZcashTheme {
        DialogView(
            state =
                ErrorState(
                    title = stringRes("Error"),
                    message = stringRes("Something went wrong"),
                    positive =
                        ButtonState(
                            text = stringRes("Positive")
                        ),
                    negative =
                        ButtonState(
                            text = stringRes("Negative")
                        ),
                    onBack = {}
                )
        )
    }
