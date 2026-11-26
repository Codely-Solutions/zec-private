package co.electriccoin.zcash.ui.screen.theme.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ThemeSettingsScreen(sourceEntryId: String) {
    val vm = koinViewModel<ThemeSettingsVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val read by vm.preferenceRead.collectAsStateWithLifecycle()
    BackHandler(onBack = vm::onBack)
    state?.let { ThemeSettingsView(state = it) }
        ?: ThemeSettingsLoadView(read.error == null, vm::onBack, vm::retryRead)
}

@Serializable
data object ThemeSettingsArgs
