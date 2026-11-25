package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.SensitiveReveal
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.usecase.CopyRecoveryToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.component.SeedTextState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

class WalletBackupViewModel(
    walletBackupMessageUseCase: WalletBackupMessageUseCase,
    getPersistableWallet: GetPersistableWalletUseCase,
    private val args: WalletBackup,
    private val navigationRouter: NavigationRouter,
    private val onUserSavedWalletBackup: OnUserSavedWalletBackupUseCase,
    private val remindWalletBackupLater: RemindWalletBackupLaterUseCase,
    private val biometricRepository: BiometricRepository,
    private val copyRecoveryToClipboard: CopyRecoveryToClipboardUseCase,
    private val appAccessSession: AppAccessSession,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    private val lockoutDuration =
        walletBackupMessageUseCase
            .observe()
            .filterIsInstance<WalletBackupData.Available>()
            .take(1)
            .map { it.lockoutDuration }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private val reveal = SensitiveReveal(appAccessSession)

    internal val isRevealed: Boolean
        get() = reveal.isRevealed

    val state =
        combine(
            reveal.changes,
            getPersistableWallet.observe(),
            lockoutDuration
        ) { grant, wallet, lockoutDuration ->
            val isRevealed = grant != null
            WalletBackupState(
                secondaryButton =
                    ButtonState(
                        text =
                            if (lockoutDuration != null) {
                                stringRes(R.string.general_remind_me_in, stringRes(lockoutDuration.res))
                            } else {
                                stringRes(R.string.recoveryPhraseDisplay_button_remindMeLater)
                            },
                        onClick = { onRemindMeLaterClick(grant) }
                    ).takeIf { isRevealed && args.isOpenedFromSeedBackupInfo },
                primaryButton =
                    ButtonState(
                        text =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo -> {
                                    stringRes(R.string.seed_recovery_saved_button)
                                }

                                isRevealed -> {
                                    stringRes(R.string.recoveryPhraseDisplay_hide)
                                }

                                else -> {
                                    stringRes(R.string.recoveryPhraseDisplay_reveal)
                                }
                            },
                        onClick =
                            if (isRevealed && args.isOpenedFromSeedBackupInfo) {
                                { onWalletBackupSavedClick(grant) }
                            } else {
                                { onRevealClick() }
                            },
                        isEnabled = wallet != null,
                        isLoading = wallet == null,
                        icon =
                            when {
                                isRevealed && args.isOpenedFromSeedBackupInfo -> null
                                isRevealed -> R.drawable.ic_seed_hide
                                else -> R.drawable.ic_seed_show
                            },
                        hapticFeedbackType = if (isRevealed) HapticFeedbackType.Confirm else null
                    ),
                info =
                    IconButtonState(
                        onClick = ::onInfoClick,
                        icon = R.drawable.ic_help
                    ),
                seed =
                    SeedTextState(
                        seed = wallet?.seedPhrase?.split?.joinToString(" ") { if (isRevealed) it else "*" }.orEmpty(),
                        isRevealed = isRevealed,
                    ),
                birthday =
                    SeedSecretState(
                        title = stringRes(R.string.recoveryPhraseDisplay_birthdayTitle),
                        text =
                            stringRes(
                                wallet
                                    ?.takeIf { isRevealed }
                                    ?.birthday
                                    ?.value
                                    ?.toString()
                                    .orEmpty()
                            ),
                        isRevealed = isRevealed,
                        tooltip =
                            SeedSecretStateTooltip(
                                title = stringRes(R.string.recoveryPhraseDisplay_birthdayTitle),
                                message = stringRes(R.string.seed_recovery_bday_tooltip_message)
                            ),
                        onClick = null,
                    ),
                copyPhrase = wallet?.takeIf { isRevealed }?.let { wallet ->
                    ButtonState(text = stringRes(R.string.recovery_copy_phrase), onClick = {
                        copyRecoveryIfRevealed(
                            isRevealed = reveal.permits(grant),
                            value = { wallet.seedPhrase.split.joinToString(" ") },
                            copy = copyRecoveryToClipboard::invoke
                        )
                    })
                },
                copyBirthday = wallet?.takeIf { isRevealed }?.let { wallet ->
                    wallet.birthday?.let { birthday ->
                        ButtonState(text = stringRes(R.string.recovery_copy_birthday), onClick = {
                            copyRecoveryIfRevealed(
                                isRevealed = reveal.permits(grant),
                                value = { birthday.value.toString() },
                                copy = copyRecoveryToClipboard::invoke
                            )
                        })
                    }
                },
                onBack = ::onBack
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT), null)

    private fun onRemindMeLaterClick(grant: AppAccessSession.Grant?) =
        viewModelScope.launch { if (reveal.permits(grant)) remindWalletBackupLater(false, sourceEntryId) }

    private fun onWalletBackupSavedClick(grant: AppAccessSession.Grant?) =
        viewModelScope.launch {
            if (reveal.permits(grant)) onUserSavedWalletBackup(sourceEntryId)
        }

    private fun onRevealClick() =
        viewModelScope.launch {
            val grant = appAccessSession.grant.value ?: return@launch
            if (!isRevealed) {
                try {
                    biometricRepository.requestBiometrics(
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.authentication_use_case_seed_recovery)
                                )
                        )
                    )
                    reveal.reveal(grant)
                } catch (_: BiometricsFailureException) {
                    // do nothing
                } catch (_: BiometricsCancelledException) {
                    // do nothing
                }
            } else {
                reveal.hide()
            }
        }

    private fun onInfoClick() {
        navigationRouter.forward(SeedInfo)
    }

    private fun onBack() = navigationRouter.back()
}

internal fun copyRecoveryIfRevealed(isRevealed: Boolean, value: () -> String, copy: (String) -> Unit) {
    if (isRevealed) {
        copy(value())
    }
}
