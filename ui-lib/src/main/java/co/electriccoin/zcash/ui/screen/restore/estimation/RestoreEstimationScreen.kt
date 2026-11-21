package co.electriccoin.zcash.ui.screen.restore.estimation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RestoreEstimationScreen(args: RestoreEstimationArgs) {
    val vm = koinViewModel<RestoreEstimationVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    SecureScreen()
    BackHandler { state.onBack() }
    co.electriccoin.zcash.ui.screen.common
        .EstimatedBlockHeightView(state)
}

@Serializable
data class RestoreEstimationArgs(
    val sessionId: String,
    val blockHeight: Long
)
