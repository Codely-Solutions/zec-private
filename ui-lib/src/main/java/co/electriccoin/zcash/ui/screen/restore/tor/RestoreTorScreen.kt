package co.electriccoin.zcash.ui.screen.restore.tor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedVM
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RestoreTorScreen(args: RestoreTorArgs, seedEntry: RestoreSeedVM) {
    val vm = koinViewModel<RestoreTorVM> { parametersOf(args, seedEntry) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) { RestoreTorView(it) }
}

@Serializable
data class RestoreTorArgs(
    val sessionId: String,
    val blockHeight: Long
)
