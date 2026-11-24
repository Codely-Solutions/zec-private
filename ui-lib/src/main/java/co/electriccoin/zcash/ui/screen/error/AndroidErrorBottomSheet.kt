package co.electriccoin.zcash.ui.screen.error

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidErrorBottomSheet(route: ErrorBottomSheet) {
    val useCase = koinInject<NavigateToErrorUseCase>()
    val args = useCase.argsFor(route)
    if (args == null) {
        UnavailableErrorView()
        return
    }
    val vm = koinViewModel<ErrorVM> { parametersOf(args, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    BottomSheetErrorView(state)
}

@Serializable
data class ErrorBottomSheet(val id: String) : ErrorRoute
