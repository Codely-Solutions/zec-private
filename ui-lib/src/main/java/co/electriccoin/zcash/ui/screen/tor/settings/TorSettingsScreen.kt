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
    val preference by vm.preference.collectAsStateWithLifecycle()
    BackHandler(onBack = vm::onBack)
    if (preference is TorPreferenceRead.Available && state.content != null) {
        LceRenderer(state = state) { TorSettingsView(state = it) }
    } else {
        TorSettingsLoadView(preference != TorPreferenceRead.Unavailable, vm::onBack, vm::retryRead)
    }
}

@Serializable
object TorSettingsArgs
