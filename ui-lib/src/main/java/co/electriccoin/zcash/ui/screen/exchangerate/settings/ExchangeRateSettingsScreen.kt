package co.electriccoin.zcash.ui.screen.exchangerate.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExchangeRateSettingsScreen(sourceEntryId: String) {
    val vm = koinViewModel<ExchangeRateSettingsVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state?.onBack() }
    state?.let { ExchangeRateSettingsView(state = it) }
}

@Serializable
data object ExchangeRateSettingsArgs
