package co.electriccoin.zcash.ui.screen.exchangerateunavailable

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeRateUnavailableScreen(sourceEntryId: String) {
    val vm = koinViewModel<ExchangeRateUnavailableVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state = state) { content -> ExchangeRateUnavailableView(state = content) }
}

@Serializable
data object ExchangeRateUnavailableArgs
