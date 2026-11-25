package co.electriccoin.zcash.ui.screen.viewingkey.importvk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepository
import co.electriccoin.zcash.ui.common.usecase.ClearClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ReadClipboardTextUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitViewOnlyKeyResult
import co.electriccoin.zcash.ui.common.usecase.SubmitViewOnlyKeyUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateUfvkUseCase
import co.electriccoin.zcash.ui.common.usecase.ViewOnlyKeyCheckException
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationState
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.date.HWDateArgs
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.showFrom
import co.electriccoin.zcash.ui.screen.viewingkey.alreadyimported.AlreadyImportedVKArgs
import co.electriccoin.zcash.ui.screen.viewingkey.scan.ScanVKArgs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Takes the full viewing key the user pastes or types and, once it is importable, goes on to ask
 * when the account first transacted, before it is named. The key stays in this view model's memory
 * and in the pending import only: it never goes into a route, the saved instance state or a log.
 *
 * The Paste action remembers the clipboard text it read. On Continue or Back, it clears the
 * clipboard only if the text still matches the pasted item.
 *
 * When the SDK fails to read the key, the general error screen opens instead of the invalid-key
 * sheet, since nothing is known about the key then, and Continue can be tried again.
 */
internal class ImportVKVM(
    private val submitViewOnlyKey: SubmitViewOnlyKeyUseCase,
    private val readClipboardText: ReadClipboardTextUseCase,
    private val clearClipboard: ClearClipboardUseCase,
    private val pendingImportRepository: ViewOnlyPendingImportRepository,
    private val navigationRouter: NavigationRouter,
    private val navigateToError: NavigateToErrorUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val text = MutableStateFlow("")

    private val isSubmitting = MutableStateFlow(false)

    private val isInvalidKeySheetVisible = MutableStateFlow(false)

    private var pastedClipboardText: String? = null

    private var submitJob: Job? = null

    private var sheetJob: Job? = null

    val state: StateFlow<ImportVKState> =
        combine(text, isSubmitting, ::createState)
            .stateIn(viewModel = this, initialValue = createState(text.value, isSubmitting.value))

    val invalidKeySheet: StateFlow<ZashiConfirmationState?> =
        isInvalidKeySheetVisible
            .map { isVisible ->
                InvalidVKSheet
                    .create(
                        keyPrefix = ValidateUfvkUseCase.keyPrefixOf(VersionInfo.NETWORK),
                        onScanClick = ::onInvalidKeyScanClick,
                        onEditClick = ::hideInvalidKeySheet,
                    ).takeIf { isVisible }
            }.stateIn(viewModel = this, initialValue = null)

    /**
     * The paste screen is the root of the import flow: every later screen (scan, birthday, name,
     * keep-open, success) is opened on top of it, so this view model is cleared only once the whole
     * flow has left the back stack. The pending key goes with it, whichever way the flow ended.
     */
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        pastedClipboardText = null
        pendingImportRepository.clear()
        super.onCleared()
    }

    private fun createState(
        text: String,
        isSubmitting: Boolean,
    ) = ImportVKState.create(
        text = text,
        isSubmitting = isSubmitting,
        onValueChange = ::onValueChange,
        onPasteClick = ::onPasteClick,
        onScanClick = ::onScanClick,
        onContinueClick = ::onContinueClick,
        onBack = ::onBack,
    )

    private fun onValueChange(value: String) {
        text.update { value }
    }

    private fun onPasteClick() {
        if (isSubmitting.value) return
        val pasted = readClipboardText() ?: return
        pastedClipboardText = pasted
        text.value = pasted
    }

    private fun onScanClick() = navigationRouter.forward(ScanVKArgs)

    private fun onContinueClick() {
        if (submitJob?.isActive == true || text.value.isBlank()) return
        submitJob =
            viewModelScope.launch {
                isSubmitting.update { true }
                try {
                    when (submitViewOnlyKey(text.value)) {
                        SubmitViewOnlyKeyResult.Accepted -> {
                            clearPasted()
                            navigationRouter.stageResultFrom(sourceEntryId) {
                                NavigationCommand.Forward(listOf(HWDateArgs(HWWalletEnrollment.ViewOnly)))
                            }
                        }

                        SubmitViewOnlyKeyResult.AlreadyImported -> {
                            clearPasted()
                            navigationRouter.stageResultFrom(sourceEntryId) {
                                NavigationCommand.Forward(listOf(AlreadyImportedVKArgs))
                            }
                        }

                        SubmitViewOnlyKeyResult.Invalid -> {
                            isInvalidKeySheetVisible.update { true }
                        }
                    }
                } catch (e: ViewOnlyKeyCheckException) {
                    navigateToError.showFrom(sourceEntryId, ErrorArgs.General(e))
                } finally {
                    isSubmitting.update { false }
                }
            }
    }

    private fun hideInvalidKeySheet() = isInvalidKeySheetVisible.update { false }

    /**
     * Hides the sheet first and opens the scanner once it has animated away.
     */
    private fun onInvalidKeyScanClick() {
        if (sheetJob?.isActive == true) return
        sheetJob =
            viewModelScope.launch {
                hideInvalidKeySheet()
                delay(350.milliseconds)
                navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.Forward(listOf(ScanVKArgs)) }
            }
    }

    /**
     * Leaves the import: the running check is cancelled, the pending key forgotten and the
     * clipboard cleared when the key was pasted.
     */
    private fun onBack() {
        submitJob?.cancel()
        clearPasted()
        pendingImportRepository.clear()
        navigationRouter.back()
    }

    private fun clearPasted() {
        val pasted = pastedClipboardText ?: return
        pastedClipboardText = null
        if (readClipboardText() == pasted) clearClipboard()
    }
}
