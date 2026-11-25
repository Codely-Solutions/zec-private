package co.electriccoin.zcash.ui.screen.tor.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TorSettingsScreen(sourceEntryId: String) {
    val vm = koinViewModel<TorSettingsVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state = state, loading = { }) { content ->
        BackHandler { content.onDismiss() }
        TorSettingsView(state = content)
    }
}

@Serializable
object TorSettingsArgs
