package co.electriccoin.zcash.ui.screen.colorpicker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.usecase.NavigateToColorPickerUseCase
import co.electriccoin.zcash.ui.design.component.colorpicker.ColorChannel
import co.electriccoin.zcash.ui.design.component.colorpicker.filterHexInput
import co.electriccoin.zcash.ui.design.component.colorpicker.parseChannelInput
import co.electriccoin.zcash.ui.design.component.colorpicker.parseHexColor
import co.electriccoin.zcash.ui.design.component.colorpicker.spectrumColor
import co.electriccoin.zcash.ui.design.component.colorpicker.spectrumPosition
import co.electriccoin.zcash.ui.design.component.colorpicker.withChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Picks a colour for [ColorPickerArgs] and reports through [NavigateToColorPickerUseCase]: every
 * change of the picked opaque colour as it happens, then one close, which keeps the last pick. The
 * caller keeps receiving picks until the sheet's navigation entry is cleared. Every close request
 * may pop the sheet while it is on top. If a request is declined or missed, picks and another close
 * remain available. A sheet restored after process death without a caller asks to dismiss itself.
 */
internal class ColorPickerVM(
    private val args: ColorPickerArgs,
    private val navigateToColorPicker: NavigateToColorPickerUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val selection = MutableStateFlow(ColorPickerSelection.initial(args.initialArgb?.let { Color(it) }))

    private var isClosed = false
    private var closeJob: Job? = null
    private val refusedCloses = MutableStateFlow(0)
    val refusedCloseCount = refusedCloses.asStateFlow()

    val state: StateFlow<ColorPickerState> =
        selection.map(::createState).stateIn(viewModel = this, initialValue = createState(selection.value))

    private fun createState(selection: ColorPickerSelection) =
        ColorPickerState.create(
            selection = selection,
            onTabSelect = ::onTabSelect,
            onColorSelect = ::pick,
            onSpectrumPick = ::onSpectrumPick,
            onChannelChange = ::onChannelChange,
            onFieldChange = ::onFieldChange,
            onBack = ::onClose,
        )

    init {
        if (!navigateToColorPicker.hasCollector()) onClose()
    }

    override fun onCleared() {
        isClosed = true
        navigateToColorPicker.onClosed(args)
        super.onCleared()
    }

    private fun onTabSelect(tab: ColorPickerTab) = select { it.copy(tab = tab) }

    private fun onSpectrumPick(position: Offset) = pick(spectrumColor(position), spectrumPosition = position)

    private fun onChannelChange(
        channel: ColorChannel,
        value: Float
    ) = pick(selection.value.color.withChannel(channel, (value * MAX_CHANNEL).roundToInt()))

    private fun onFieldChange(
        field: ColorPickerField,
        text: String
    ) = select { current ->
        val channel = field.channel
        val draft = if (channel == null) filterHexInput(text) else text.filter(Char::isDigit).take(CHANNEL_DIGITS)
        val parsed =
            if (channel == null) {
                parseHexColor(draft)
            } else {
                parseChannelInput(draft)?.let { current.color.withChannel(channel, it) }
            }
        current.copy(
            color = parsed ?: current.color,
            spectrumPosition = parsed?.spectrumPosition() ?: current.spectrumPosition,
            drafts = mapOf(field to draft)
        )
    }

    private fun onClose() {
        if (closeJob?.isActive == true || isClosed) return
        closeJob = viewModelScope.launch {
            if (!navigateToColorPicker.dismiss(args, sourceEntryId)) refusedCloses.update { it + 1 }
        }
    }

    /** Picks the opaque part of [color], which clears every half-typed field. */
    private fun pick(
        color: Color,
        spectrumPosition: Offset = color.spectrumPosition(),
    ) = select {
        it.copy(
            color = color.copy(alpha = 1f),
            spectrumPosition = spectrumPosition,
            drafts = emptyMap()
        )
    }

    /** Applies [transform] to the selection and reports the picked colour when it changed, until closed. */
    private fun select(transform: (ColorPickerSelection) -> ColorPickerSelection) {
        val before = selection.value.color
        val after = selection.updateAndGet(transform).color
        if (after != before && !isClosed) navigateToColorPicker.onPicked(after, args)
    }

    private companion object {
        const val MAX_CHANNEL = 255
        const val CHANNEL_DIGITS = 3
    }
}
