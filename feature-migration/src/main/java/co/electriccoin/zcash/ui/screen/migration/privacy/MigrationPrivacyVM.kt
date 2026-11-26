package co.electriccoin.zcash.ui.screen.migration.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import co.electriccoin.zcash.ui.design.R as DesignR

class MigrationPrivacyVM(
    private val args: MigrationPrivacyArgs,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    // Always shown with Tor defaulted ON, independent of the global setting's actual current
    // value — the sheet is only ever reached when the global setting is off, but the migration's
    // own choice still starts from the privacy-preferred default rather than mirroring that off
    // state.
    private val useTor = MutableStateFlow(true)

    val state: StateFlow<MigrationPrivacyState?> =
        useTor
            .map { tor ->
                MigrationPrivacyState(
                    body =
                        when (args.mode) {
                            MigrationMode.IMMEDIATE -> stringRes(DesignR.string.migrationPrivacy_bodyImmediate)
                            MigrationMode.AUTOMATIC -> stringRes(DesignR.string.migrationPrivacy_bodyAutomatic)
                        },
                    checkbox =
                        if (args.mode == MigrationMode.AUTOMATIC) CheckboxState(
                            title = stringRes(DesignR.string.migration_common_enableTorProtectionTitle),
                            subtitle = stringRes(DesignR.string.migration_common_torCheckboxSubtitle),
                            isChecked = tor,
                            onClick = { onTorToggle(!tor) },
                        ) else null,
                    confirmLabel = if (args.mode == MigrationMode.IMMEDIATE) {
                        stringRes(DesignR.string.migration_common_continueWithoutTor)
                    } else {
                        null
                    },
                    onConfirm = ::onConfirm,
                    onBack = ::onBack,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null,
            )

    private fun onTorToggle(enabled: Boolean) {
        useTor.value = enabled
    }

    private fun onConfirm() {
        navigationRouter.forward(
            when (args.mode) {
                MigrationMode.IMMEDIATE -> MigrationReviewArgs(args.mode, args.accountKeyId)
                MigrationMode.AUTOMATIC -> MigrationBatteryArgs(args.accountKeyId, useTor.value, args.recoveryId)
            }
        )
    }

    private fun onBack() = navigationRouter.back()
}
