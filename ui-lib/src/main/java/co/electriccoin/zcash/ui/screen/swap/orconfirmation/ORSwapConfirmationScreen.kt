package co.electriccoin.zcash.ui.screen.swap.orconfirmation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.error.DialogView
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.UnavailableWalletAction
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ORSwapConfirmationScreen(sourceEntryId: String) {
    val vm = koinViewModel<ORSwapConfirmationVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val dialogState by vm.dialogState.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val current = state
    if (current != null) {
        BackHandler(onBack = current.onBack)
        ORSwapConfirmationView(current)
    } else if (saving) {
        BackHandler(onBack = vm::openActivity)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (dialogState == null) {
        UnavailableWalletAction(
            screenTitle = stringRes(R.string.swapToZec_review),
            title = stringRes(R.string.private_deposit_unavailable_title),
            message = stringRes(R.string.private_deposit_unavailable_message),
            action = ButtonState(
                text = stringRes(R.string.private_recovery_open_activity),
                onClick = vm::openActivity,
            ),
        )
    }
    dialogState?.let { DialogView(state = it) }
}

@Serializable
data object ORSwapConfirmationArgs
