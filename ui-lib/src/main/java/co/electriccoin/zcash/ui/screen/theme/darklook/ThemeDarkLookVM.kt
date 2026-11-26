package co.electriccoin.zcash.ui.screen.theme.darklook

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.provider.AppearanceModeStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsOledEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.getOrSystem
import co.electriccoin.zcash.ui.common.usecase.SetAppearanceModeUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ThemeDarkLookVM(
    private val args: ThemeDarkLookArgs,
    private val navigationRouter: NavigationRouter,
    private val appearanceModeStorageProvider: AppearanceModeStorageProvider,
    private val isOledEnabledStorageProvider: IsOledEnabledStorageProvider,
    private val setAppearanceMode: SetAppearanceModeUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private var storedMode = AppearanceMode.SYSTEM

    private var storedOledEnabled = false

    private val selectedOledEnabled = MutableStateFlow<Boolean?>(null)
    private val save = mutableLce<Unit>()

    val state =
        combine(selectedOledEnabled, save.state) { oledEnabled, result ->
            oledEnabled?.let { createState(it, result.loading, result.error != null) }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
        viewModelScope.launch {
            storedMode = appearanceModeStorageProvider.getOrSystem()
            storedOledEnabled = isOledEnabledStorageProvider.get() == true
            selectedOledEnabled.update { storedOledEnabled }
        }
    }

    private fun createState(oledEnabled: Boolean, isSaving: Boolean, hasSaveError: Boolean) =
        ThemeDarkLookState(
            options =
                listOf(false, true).map { isOled ->
                    DarkLookOptionState(
                        isOledEnabled = isOled,
                        isChecked = isOled == oledEnabled,
                        onClick = { onOptionClick(isOled) }
                    )
                },
            saveButton =
                ButtonState(
                    stringRes(R.string.currencyConversion_saveBtn),
                    onClick = ::onSaveClick,
                    isEnabled = !isSaving && isSaveEnabled(oledEnabled),
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ),
            onBack = ::onBack,
            hasSaveError = hasSaveError,
        )

    /**
     * Save commits two values - [ThemeDarkLookArgs.mode] and the dark look - and it is the only path that
     * persists the mode for [AppearanceMode.SYSTEM] and [AppearanceMode.DARK]. So it stays enabled whenever
     * either one differs from what is stored; gating on the look alone would make switching to a mode while
     * keeping the current look unreachable.
     */
    private fun isSaveEnabled(oledEnabled: Boolean) = oledEnabled != storedOledEnabled || args.mode != storedMode

    private fun onOptionClick(isOledEnabled: Boolean) = selectedOledEnabled.update { isOledEnabled }

    private fun onBack() = navigationRouter.back()

    private fun onSaveClick() = save.guardLoading {
        val oledEnabled = selectedOledEnabled.value ?: return@guardLoading
        if (isSaveEnabled(oledEnabled)) save.execute {
            setAppearanceMode(
                args.mode,
                isOledEnabled = oledEnabled,
                sourceEntryId = sourceEntryId,
            )
            storedMode = args.mode
            storedOledEnabled = oledEnabled
        }
    }
}
