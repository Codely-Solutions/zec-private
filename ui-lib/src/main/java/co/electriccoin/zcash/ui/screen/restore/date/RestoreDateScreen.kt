package co.electriccoin.zcash.ui.screen.restore.date

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.screen.common.BirthdayPickerView
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun RestoreDateScreen(args: RestoreDateArgs) {
    val vm = koinViewModel<RestoreDateVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    SecureScreen()
    LceRenderer(state) {
        BackHandler { it.onBack() }
        BirthdayPickerView(it)
    }
}

@Serializable
data class RestoreDateArgs(
    val sessionId: String
)
