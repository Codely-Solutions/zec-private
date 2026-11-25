package co.electriccoin.zcash.ui.screen.exportvk.confirm

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportVKConfirmScreen(args: ExportVKConfirmArgs) {
    val vm = koinViewModel<ExportVKConfirmVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    ExportVKConfirmView(state = state)
}

@Serializable
data class ExportVKConfirmArgs(val reviewId: String)
