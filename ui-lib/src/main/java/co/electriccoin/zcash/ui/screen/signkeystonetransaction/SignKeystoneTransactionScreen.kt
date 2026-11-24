package co.electriccoin.zcash.ui.screen.signkeystonetransaction

import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.UnavailableWalletAction
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignKeystoneTransactionScreen() {
    val vm = koinViewModel<SignKeystoneTransactionVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val bottomSheetState by vm.bottomSheetState.collectAsStateWithLifecycle()
    val current = state
    if (current == null) {
        UnavailableWalletAction(
            screenTitle = stringRes(R.string.keystone_signWith_signTransaction),
            title = stringRes(R.string.private_keystone_unavailable_title),
            message = stringRes(R.string.private_keystone_unavailable_message),
            action = ButtonState(text = stringRes(R.string.general_back), onClick = vm::leaveSigning),
        )
    } else {
        BackHandler(onBack = current.onBack)
        SignKeystoneTransactionView(current)
    }
    SignKeystoneTransactionBottomSheet(state = bottomSheetState)
}

@Serializable
object SignKeystoneTransactionArgs
