package co.electriccoin.zcash.ui.screen.migration.customservertor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chooseserver.ChooseServerArgs
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import co.electriccoin.zcash.ui.design.R as DesignR

class MigrationCustomServerTorVM(
    private val args: MigrationCustomServerTorArgs,
    private val navigationRouter: NavigationRouter,
) : ViewModel() {
    val state: StateFlow<MigrationCustomServerTorState?> =
        flowOf(
            MigrationCustomServerTorState(
                body =
                    when (args.mode) {
                        MigrationMode.IMMEDIATE -> stringRes(DesignR.string.migrationCustomServerTor_bodyImmediate)
                        MigrationMode.AUTOMATIC -> stringRes(DesignR.string.migrationCustomServerTor_bodyAutomatic)
                    },
                riskBody =
                    when (args.mode) {
                        MigrationMode.IMMEDIATE -> stringRes(DesignR.string.migrationCustomServerTor_riskBodyImmediate)
                        MigrationMode.AUTOMATIC -> stringRes(DesignR.string.migrationCustomServerTor_riskBodyAutomatic)
                    },
                onContinueWithoutTor = ::onContinueWithoutTor,
                onSwitchServer = ::onSwitchServer,
                onBack = ::onBack,
            )
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    private fun onContinueWithoutTor() {
        navigationRouter.forward(
            when (args.mode) {
                MigrationMode.IMMEDIATE -> MigrationReviewArgs(args.mode, args.accountKeyId)
                MigrationMode.AUTOMATIC ->
                    MigrationBatteryArgs(args.accountKeyId, useTor = false, recoveryId = args.recoveryId)
            }
        )
    }

    private fun onSwitchServer() = navigationRouter.forward(ChooseServerArgs)

    private fun onBack() = navigationRouter.back()
}
