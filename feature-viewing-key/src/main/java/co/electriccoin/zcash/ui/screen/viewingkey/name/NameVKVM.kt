package co.electriccoin.zcash.ui.screen.viewingkey.name

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.AccountNameLimit
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.ImportViewOnlyAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsAccountNameTakenUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToColorPickerUseCase
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Names the pending view-only account, the last step of the import: the key and the birthday the
 * earlier screens collected are held with the pending import, and Continue imports the account with
 * the name and the colour picked here. The name is capped at [AccountNameLimit.MAX_LENGTH] code
 * points as it is typed. Without a pending key, after process death, there is nothing to name and
 * the flow returns to the root. The avatar's pencil badge opens the colour picker, the avatar follows
 * the picks live and keeps the last one however the picker closes. A name another account already
 * shows, by [IsAccountNameTakenUseCase], disables Continue and says so under the field.
 *
 * While the import runs, Continue shows its progress and the name, the colour and back are ignored;
 * a failed import shows the error, from which it can be tried again.
 */
internal class NameVKVM(
    private val pendingImportRepository: ViewOnlyPendingImportRepository,
    private val navigationRouter: NavigationRouter,
    private val navigateToColorPicker: NavigateToColorPickerUseCase,
    private val isAccountNameTaken: IsAccountNameTakenUseCase,
    private val importViewOnlyAccount: ImportViewOnlyAccountUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }
    private val name = MutableStateFlow(pendingImportRepository.get()?.name.orEmpty())

    private val color = MutableStateFlow(pendingImportRepository.get()?.color)

    private val importLce = mutableLce<Unit>()

    private var colorPickerJob: Job? = null

    private var continueJob: Job? = null

    private var leaving = false

    /**
     * The typed name with whether another account already shows it, which the new name must not
     * repeat; until the accounts load, the name shows without the error.
     */
    val state: StateFlow<LceState<NameVKState>> =
        combine(
            isAccountNameTaken.observe(name), color, importLce.state, pendingImportRepository.pendingImport
        ) { check, color, lce, pending ->
            val approved = pending?.takeIf { it.importedAccount != null }
            createState(
                approved?.name ?: check.name,
                if (approved != null) approved.color else color,
                isDuplicate = approved == null && check.isTaken,
                isImporting = lce.loading,
                committedBirthday = approved?.committedBirthday,
            )
        }.withLce(importLce, errorStateMapper::mapToState)
            .stateIn(
                viewModel = this,
                initialValue =
                    LceState(
                        content = createState(
                            name.value, color.value, isDuplicate = false, isImporting = false,
                            committedBirthday = pendingImportRepository.get()?.committedBirthday,
                        )
                    )
            )

    init {
        if (pendingImportRepository.get() == null) {
            navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
        }
    }

    private fun createState(
        name: String,
        color: Color?,
        isDuplicate: Boolean,
        isImporting: Boolean,
        committedBirthday: CommittedEnrollmentBirthday?,
    ) = NameVKState.create(
        name = name,
        color = color,
        isDuplicate = isDuplicate,
        isImporting = isImporting,
        committedBirthday = committedBirthday,
        onAvatarEditClick = ::onAvatarEditClick,
        onValueChange = ::onValueChange,
        onContinueClick = { onContinueClick(committedBirthday) },
        onBack = ::onBack,
    )

    private fun onAvatarEditClick() =
        importLce.guardLoading {
            if (leaving || pendingImportRepository.get()?.importedAccount != null) return@guardLoading
            if (colorPickerJob?.isActive == true) return@guardLoading
            colorPickerJob =
                viewModelScope.launch {
                    navigateToColorPicker(color.value).collect { picked -> color.update { picked } }
                }
        }

    private fun onValueChange(value: String) =
        importLce.guardLoading {
            if (!leaving && pendingImportRepository.get()?.importedAccount == null) {
                name.update { AccountNameLimit.cap(value) }
            }
        }

    /**
     * A second tap while the import runs is ignored: restarting would cancel the running import
     * inside the SDK call, after the account exists but before it is selected.
     */
    private fun onContinueClick(committedBirthday: CommittedEnrollmentBirthday?) =
        importLce.guardLoading {
            if (leaving || continueJob?.isActive == true) return@guardLoading
            val pending = pendingImportRepository.get()
            if (pending?.committedBirthday != committedBirthday) return@guardLoading
            val normalized = AccountNameLimit.normalize(name.value)
            if (normalized.isEmpty()) return@guardLoading
            continueJob =
                viewModelScope.launch {
                    if (pendingImportRepository.get() !== pending) return@launch
                    if (pending?.birthday == null) {
                        navigationRouter.backToRoot()
                        return@launch
                    }
                    if (pending.importedAccount == null) {
                        if (isAccountNameTaken(normalized)) return@launch
                        val approved = pending.copy(name = normalized, color = color.value)
                        if (!pendingImportRepository.replace(pending, approved)) return@launch
                    }
                    importLce.execute {
                        try {
                            importViewOnlyAccount(sourceEntryId)
                        } catch (_: ViewOnlyPendingImportMissingException) {
                            navigationRouter.backToRoot()
                        }
                    }
                }
        }

    /** Returns to the birthday screen, retaining any import the SDK has already completed. */
    private fun onBack() =
        importLce.guardLoading {
            if (leaving) return@guardLoading
            leaving = true
            colorPickerJob?.cancel()
            continueJob?.cancel()
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                leaving = navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back }
            }
        }
}
