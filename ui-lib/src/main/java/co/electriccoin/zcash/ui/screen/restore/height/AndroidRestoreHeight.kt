package co.electriccoin.zcash.ui.screen.restore.height

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AndroidRestoreHeight(args: RestoreHeight, cancelSession: () -> Unit) {
    val vm = koinViewModel<RestoreHeightVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    SecureScreen()
    val onBack = {
        cancelSession()
        state.onBack()
    }
    BackHandler(onBack = onBack)
    co.electriccoin.zcash.ui.screen.common
        .BlockHeightView(state.copy(onBack = onBack))
}

@Serializable
data class RestoreHeight(
    val sessionId: String
)
