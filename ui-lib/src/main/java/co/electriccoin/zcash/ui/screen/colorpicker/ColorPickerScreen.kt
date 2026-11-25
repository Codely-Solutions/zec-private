package co.electriccoin.zcash.ui.screen.colorpicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.UUID

@Composable
internal fun ColorPickerScreen(args: ColorPickerArgs, sourceEntryId: String) {
    val vm = koinViewModel<ColorPickerVM> { parametersOf(args, sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refusedCloseCount by vm.refusedCloseCount.collectAsStateWithLifecycle()
    ColorPickerView(state, refusedCloseCount)
}

/**
 * Opens the colour picker sheet.
 *
 * @param initialArgb the colour it starts on as ARGB, or null to start on white.
 */
@Serializable
data class ColorPickerArgs(
    val initialArgb: Int?,
    val requestId: String = UUID.randomUUID().toString()
)
