package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwarePolicy
import co.electriccoin.zcash.ui.common.model.KeystoneFirmwareVersion
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.toKeystoneFwVersion
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.isMigrationPausedUntilNetworkUpgrade
import co.electriccoin.zcash.ui.common.usecase.isMigrationWalletTipUnavailable
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchRoundSlice
import co.electriccoin.zcash.ui.screen.migration.keystonesign.keystoneBatchTotalRounds
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import co.electriccoin.zcash.ui.design.R as DesignR

class MigrationKeystoneScanVM(
    private val args: MigrationKeystoneScanArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val pendingSchedule: PendingMigrationScheduleRepository,
    private val pendingKeystonePczts: PendingKeystoneMigrationPcztsRepository,
    private val navigationRouter: NavigationRouter,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    val validationState = MutableStateFlow(ScanValidationState.NONE)

    val state =
        MutableStateFlow(
            ScanKeystoneState(
                progress = null,
                message = stringRes(DesignR.string.migrationKeystoneScan_instructions),
            )
        )

    val failureSheet = MutableStateFlow<MigrationTransferFailureState?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isScanReady = getSelectedWalletAccount.observe()
        .flatMapLatest { account ->
            val key = account?.sdkAccount?.accountUuid?.toStorageKeyId() ?: return@flatMapLatest flowOf(null)
            if (args.accountKeyId != null && args.accountKeyId != key) {
                flowOf(false)
            } else {
                pendingKeystonePczts.observeBatch(key).map { batch ->
                    pendingSchedule.peek(key) != null && batch?.isFullySigned == false
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    private var isProcessing = false
    private var hasResetDecoder = false

    // "cypherpunk" 3.0.2 is the first Keystone firmware that supports migration batch signing at
    // all — older firmware either can't sign the batch correctly or won't report a version, and
    // both cases must block broadcast, not silently proceed.
    private val requiredFirmware = KeystoneFirmwareVersion(displayMajor = 3, minor = 0, build = 2)

    fun onScanned(result: String) {
        if (isProcessing) return
        isProcessing = true
        viewModelScope.launch {
            try {
                val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
                check(args.accountKeyId == null || args.accountKeyId == accountKeyId) {
                    "The scan belongs to a different account"
                }
                val sched = pendingSchedule.get(accountKeyId)
                val pending = pendingKeystonePczts.get(accountKeyId)
                if (sched == null || pending == null) {
                    // A restored Sign screen has no usable request either.
                    navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
                    return@launch
                }
                if (pending.isFullySigned) {
                    openAfterSigning(MigrationScheduledArgs(accountKeyId))
                    return@launch
                }
                val sdk = checkNotNull(getOrchardMigrationSdk(accountKeyId)) { "Migration account is unavailable" }
                if (!hasResetDecoder) {
                    sdk.resetKeystoneSignBatchDecoder()
                    hasResetDecoder = true
                }
                val decoded =
                    runCatching { sdk.decodeKeystoneSignBatchPart(result, pending.requestId) }
                        .getOrElse {
                            isProcessing = false
                            return@launch
                        }
                state.update { it.copy(progress = decoded.progress) }
                val data = decoded.data
                if (!decoded.complete || data == null) {
                    isProcessing = false
                    return@launch
                }

                // Firmware can't change mid-batch (same physical device every round), so checking on
                // round 0 only is sufficient and avoids making the user scan through every remaining
                // round only to be blocked at the very end.
                if (pending.roundIndex == 0) {
                    val detected = decoded.firmwareVersion?.toKeystoneFwVersion()
                    val outcome = KeystoneFirmwarePolicy.evaluate(detected, requiredFirmware)
                    migrationLog(
                        "MigrationKeystoneScanVM: detected Keystone firmware " +
                            "${detected ?: "none"} (required $requiredFirmware) -> $outcome"
                    )
                    if (outcome != KeystoneFirmwarePolicy.Outcome.OK) {
                        isProcessing = false
                        failureSheet.update {
                            MigrationTransferFailureState(
                                message = stringRes(DesignR.string.migrationKeystoneScan_firmwareUnsupported),
                                // Nothing to retry without a physical firmware update — both actions
                                // just dismiss and back out, unlike the network-failure sheet below.
                                onRetry = {
                                    failureSheet.value = null
                                    navigationRouter.back()
                                },
                                onDismiss = {
                                    failureSheet.value = null
                                    navigationRouter.back()
                                },
                            )
                        }
                        return@launch
                    }
                }

                // This round's slice only — the scanned response covers exactly what buildBatch()
                // built for pending.roundIndex, not the whole (possibly multi-round) batch.
                val roundBudget = sdk.keystoneSigningRoundBudget()
                val slice =
                    keystoneBatchRoundSlice(
                        roundIndex = pending.roundIndex,
                        hasSplit = pending.splitUnsignedPczt != null,
                        prepCount = pending.prepUnsignedPczts.size,
                        transferCount = pending.transferUnsignedPczts.size,
                        budget = roundBudget,
                    )
                val prepsForRound = pending.prepUnsignedPczts.slice(slice.prepRange)
                val transfersForRound = pending.transferUnsignedPczts.slice(slice.transferRange)
                val splitForRound = if (slice.includeSplit) pending.splitUnsignedPczt else null

                val signed =
                    sdk.applyKeystoneBatchSignatures(
                        splitUnsignedPczt = splitForRound?.let(::Pczt),
                        // Same [preps..., transfers...] order the sign screen built the QR with — the
                        // response list aligns positionally, split back by the same counts below.
                        transferUnsignedPczts = (prepsForRound + transfersForRound).map { Pczt(it.second) },
                        batchSignResponse = data,
                    )
                migrationLog(
                    "KeystoneScan: round ${pending.roundIndex} signatures applied " +
                        "(split=${signed.splitSignedPczt != null}, preps=${prepsForRound.size}, " +
                        "transfers=${transfersForRound.size})"
                )

                val accumulatedSplitSigned = signed.splitSignedPczt?.toByteArray() ?: pending.accumulatedSplitSigned
                val signedPreps = signed.transferSignedPczts.take(prepsForRound.size).map { it.toByteArray() }
                val signedTransfers = signed.transferSignedPczts.drop(prepsForRound.size).map { it.toByteArray() }
                val accumulatedPrepSigned =
                    pending.accumulatedPrepSigned +
                        prepsForRound.map { it.first }.zip(signedPreps)
                val accumulatedTransferSigned =
                    pending.accumulatedTransferSigned +
                        transfersForRound.map { it.first }.zip(signedTransfers)

                val totalRounds =
                    keystoneBatchTotalRounds(
                        hasSplit = pending.splitUnsignedPczt != null,
                        prepCount = pending.prepUnsignedPczts.size,
                        transferCount = pending.transferUnsignedPczts.size,
                        budget = roundBudget,
                    )
                if (pending.roundIndex + 1 < totalRounds) {
                    // More rounds remain — carry the accumulated signatures forward and hand off to a
                    // fresh sign-screen instance for the next round. Replace the completed Sign and Scan.
                    //
                    // isProcessing deliberately stays true from here on: the Keystone device doesn't
                    // rotate/clear its response QR after being scanned, and ImageAnalysis keeps
                    // re-decoding that still-visible frame every frame. Navigation
                    // isn't synchronous, so without this guard a re-entrant onScanned during the
                    // transition would re-fetch `pending` with roundIndex already advanced past this
                    // round, hand keystoneBatchRoundSlice an out-of-range index, and silently apply the
                    // real signature against an all-empty slice — or on the last round, crash Rust's
                    // apply_batch_signatures with "expected 0" for a response that still carries one.
                    // This VM instance is being navigated away from either way, so it should never
                    // process another scan.
                    pendingKeystonePczts.set(
                        accountKeyId,
                        pending.copy(
                            roundIndex = pending.roundIndex + 1,
                            accumulatedSplitSigned = accumulatedSplitSigned,
                            accumulatedPrepSigned = accumulatedPrepSigned,
                            accumulatedTransferSigned = accumulatedTransferSigned,
                        )
                    )
                    migrationLog(
                        "KeystoneScan: round ${pending.roundIndex} done — handing off to round " +
                            "${pending.roundIndex + 1} of $totalRounds"
                    )
                    openAfterSigning(MigrationKeystoneSignArgs(args.mode, accountKeyId))
                    return@launch
                }

                // Last (or only) round — persist the FULL accumulated signed set (not just this
                // round's slice) and hand off to MigrationScheduledVM, which performs the actual
                // network/JNI work (Tor submit, schedule storage, finalize) while rendering its own
                // loading state — this screen has no more feedback to give once scanning is done.
                //
                // isProcessing deliberately stays true — see the comment above the other hand-off
                // branch; the same still-visible-QR re-entrancy is what produced the "expected 0"
                // crash this guard exists to prevent.
                pendingKeystonePczts.set(
                    accountKeyId,
                    pending.copy(
                        roundIndex = pending.roundIndex + 1,
                        accumulatedSplitSigned = accumulatedSplitSigned,
                        accumulatedPrepSigned = accumulatedPrepSigned,
                        accumulatedTransferSigned = accumulatedTransferSigned,
                        isFullySigned = true,
                    )
                )
                migrationLog(
                    "KeystoneScan: round ${pending.roundIndex} (final) done — handing off to MigrationScheduledVM"
                )
                openAfterSigning(MigrationScheduledArgs(accountKeyId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Any unguarded failure here (e.g. a transient "database is locked" from the
                // migration-engine mutex) must not crash the app mid-Keystone-ceremony — the user
                // is holding a hardware device expecting the app to still be there. Leave
                // isProcessing false so the still-visible QR can be rescanned instead of forcing a
                // full restart of the batch.
                migrationLog("MigrationKeystoneScanVM: scan failed (${e.javaClass.simpleName}).")
                isProcessing = false
                failureSheet.update {
                    MigrationTransferFailureState(
                        message =
                            when {
                                e.isMigrationPausedUntilNetworkUpgrade() -> {
                                    stringRes(DesignR.string.migrationUpgradePaused_message)
                                }

                                e.isMigrationWalletTipUnavailable() -> {
                                    stringRes(DesignR.string.migrationTipUnavailable_message)
                                }

                                else -> {
                                    stringRes(DesignR.string.migrationKeystoneScan_errorMessage)
                                }
                            },
                        onRetry = { failureSheet.value = null },
                        onDismiss = {
                            failureSheet.value = null
                            navigationRouter.back()
                        },
                    )
                }
            }
        }
    }

    private fun openAfterSigning(route: Any) = navigationRouter.stageResultFrom(sourceEntryId) {
        NavigationCommand.ReplaceFrom(MigrationKeystoneSignArgs::class, listOf(route))
    }

    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    fun onBack() = navigationRouter.back()

    fun recoverFromHome() = navigationRouter.backToRoot()
}
