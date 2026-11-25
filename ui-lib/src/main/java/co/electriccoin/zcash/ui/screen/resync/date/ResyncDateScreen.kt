package co.electriccoin.zcash.ui.screen.resync.date

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
fun ResyncDateScreen(args: ResyncDateArgs, sourceEntryId: String) {
    val vm = koinViewModel<ResyncDateVM> { parametersOf(args, sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    SecureScreen()
    LceRenderer(state) {
        BackHandler { it.onBack() }
        BirthdayPickerView(it)
    }
}

@Serializable
data class ResyncDateArgs(
    val uuid: String,
    val initialBlockHeight: Long
)
