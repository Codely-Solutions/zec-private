package co.electriccoin.zcash.ui.screen.theme.settings

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.provider.AppearanceModeStorageProvider
import co.electriccoin.zcash.ui.common.provider.getOrSystem
import co.electriccoin.zcash.ui.common.usecase.SetAppearanceModeUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.theme.darklook.ThemeDarkLookArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal class ThemeSettingsVM(
    private val navigationRouter: NavigationRouter,
    private val appearanceModeStorageProvider: AppearanceModeStorageProvider,
    private val setAppearanceMode: SetAppearanceModeUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private var storedMode = AppearanceMode.SYSTEM

    private val selectedMode = MutableStateFlow<AppearanceMode?>(null)
    private val save = mutableLce<Unit>()
    private val read = mutableLce<Unit>()
    val preferenceRead = read.state

    val state =
        combine(selectedMode, save.state) { mode, result ->
            mode?.let { createState(it, result.loading, result.error != null) }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
        retryRead()
    }

    fun retryRead() = read.guardLoading {
        read.execute {
            storedMode = appearanceModeStorageProvider.getOrSystem()
            selectedMode.update { storedMode }
        }
    }

    private fun createState(selectedMode: AppearanceMode, isSaving: Boolean, hasSaveError: Boolean) =
        ThemeSettingsState(
            options =
                listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK).map { mode ->
                    AppearanceModeOptionState(
                        mode = mode,
                        isChecked = mode == selectedMode,
                        onClick = { onModeClick(mode) }
                    )
                },
            saveButton =
                ButtonState(
                    stringRes(R.string.currencyConversion_saveBtn),
                    onClick = ::onSaveClick,
                    isEnabled = !isSaving && selectedMode != storedMode,
                    hapticFeedbackType = HapticFeedbackType.Confirm
                ),
            onBack = ::onBack,
            hasSaveError = hasSaveError,
        )

    fun onBack() = navigationRouter.back()

    /**
     * [AppearanceMode.LIGHT] never renders dark, so it's selected directly. [AppearanceMode.SYSTEM] and
     * [AppearanceMode.DARK] can both resolve to a dark appearance, so tapping either one opens the dark-look
     * sheet instead of changing the local selection - the sheet itself persists the mode once a look is chosen.
     */
    private fun onModeClick(mode: AppearanceMode) {
        if (mode == AppearanceMode.LIGHT) {
            selectedMode.update { mode }
        } else {
            navigationRouter.forward(ThemeDarkLookArgs(mode))
        }
    }

    private fun onSaveClick() = save.guardLoading {
        val mode = selectedMode.value
        if (mode != AppearanceMode.LIGHT) {
            Twig.warn {
                "Ignoring save for $mode - only AppearanceMode.LIGHT is selectable here, System and Dark " +
                    "forward to the dark-look sheet, which persists the mode together with isOledEnabled."
            }
            return@guardLoading
        }
        if (mode != storedMode) save.execute {
            setAppearanceMode(mode, isOledEnabled = false, sourceEntryId = sourceEntryId)
            storedMode = mode
        }
    }
}
