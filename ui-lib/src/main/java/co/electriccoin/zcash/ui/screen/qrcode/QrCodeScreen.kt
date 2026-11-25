package co.electriccoin.zcash.ui.screen.qrcode

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun QrCodeScreen(addressType: Int, sourceEntryId: String) {
    val vm = koinViewModel<QrCodeVM> { parametersOf(addressType, sourceEntryId) }
    val qrCodeState by vm.state.collectAsStateWithLifecycle()
    QrCodeScreenContent(qrCodeState)
}

@Composable
internal fun QrCodeScreenContent(qrCodeState: QrCodeState) {
    val snackbarHostState = remember { SnackbarHostState() }
    BackHandler { qrCodeState.onBack() }
    QrCodeView(
        state = qrCodeState,
        snackbarHostState = snackbarHostState
    )
}
