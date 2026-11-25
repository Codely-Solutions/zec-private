package co.electriccoin.zcash.ui.screen.tor.optin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TorOptInScreen(sourceEntryId: String) {
    val vm = koinViewModel<TorOptInVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state = state, loading = { }) { content ->
        BackHandler { content.onBack() }
        TorOptInView(state = content)
    }
}

@Serializable
object TorOptInArgs
