package co.electriccoin.zcash.ui.screen.exportvk.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.UnavailableWalletAction
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun VKDetailScreen(args: VKDetailArgs, sourceEntryId: String) {
    val vm = koinViewModel<VKDetailVM> { parametersOf(args, sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val unavailable by vm.isUnavailable.collectAsStateWithLifecycle()
    VKDetailScreenContent(state, unavailable, { vm.isRevealed }, vm::onBack)
}

@Composable
internal fun VKDetailScreenContent(
    state: VKDetailState?,
    unavailable: Boolean,
    isRevealed: () -> Boolean,
    onBack: () -> Unit,
) {
    if (unavailable) {
        UnavailableWalletAction(
            screenTitle = stringRes(R.string.exportViewingKey_topBar),
            title = stringRes(R.string.private_viewing_key_unavailable_title),
            message = stringRes(R.string.private_viewing_key_unavailable_message),
            action = ButtonState(text = stringRes(R.string.general_back), onClick = onBack),
        )
    } else {
        BackHandler(onBack = onBack)
        if (state == null) {
            CircularScreenProgressIndicator()
        } else if (!state.content.isRevealed || isRevealed()) {
            VKDetailView(state = state)
        }
    }
}

@Serializable
data class VKDetailArgs(
    val type: VKType,
    val reviewId: String,
)
