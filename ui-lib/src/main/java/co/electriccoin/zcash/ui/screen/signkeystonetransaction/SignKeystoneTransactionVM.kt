package co.electriccoin.zcash.ui.screen.signkeystonetransaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneProposalPCZTEncoderUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.SharePCZTUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.addressbook.ADDRESS_MAX_LENGTH
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import com.sparrowwallet.hummingbird.UREncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SignKeystoneTransactionVM(
    getWalletAccounts: GetWalletAccountsUseCase,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val createKeystoneProposalPCZTEncoder: CreateKeystoneProposalPCZTEncoderUseCase,
    private val cancelKeystoneProposalFlow: CancelProposalFlowUseCase,
    private val sharePCZT: SharePCZTUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val retryRequests = MutableStateFlow(0)
    private val preparation = MutableStateFlow<Preparation?>(null)
    private val proposals = keystoneProposalRepository.transactionProposal
    private val originalApproval = keystoneProposalRepository.signingPreparationId()
    private val approvalIds = keystoneProposalRepository.signingPreparationIds
    private val wallets = getWalletAccounts.observe()
    // The repository holds a single approved proposal even when the selected Keystone account changes.
    private var signingAccount = wallets.value.selectedKeystone()?.sdkAccount?.accountUuid
    private val rejection = MutableStateFlow<Attempt?>(null)

    val bottomSheetState =
        combine(rejection, proposals, wallets, preparation, approvalIds) { attempt, _, _, _, _ ->
            if (attempt == null || !isCurrent(attempt)) return@combine null
            SignKeystoneTransactionBottomSheetState(
                onBack = { closeRejection(attempt) },
                positiveButton =
                    ButtonState(
                        text = stringRes(R.string.keystoneTransactionReject_goBack),
                        onClick = { closeRejection(attempt) }
                    ),
                negativeButton =
                    ButtonState(
                        text = stringRes(R.string.keystoneTransactionReject_rejectSig),
                        onClick = { confirmRejection(attempt) }
                    ),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT, replayExpiration = Duration.ZERO),
            initialValue = null
        )

    val state: StateFlow<SignKeystoneTransactionState?> =
        combine(
            proposals,
            wallets,
            preparation,
            approvalIds,
        ) { proposal, wallets, prepared, approvalId ->
            if (proposal == null || approvalId == null || approvalId != originalApproval) return@combine null
            if (proposal is ShieldTransactionProposal && proposal.approval?.isCurrent() != true) {
                return@combine null
            }
            val wallet = wallets.selectedKeystone() ?: return@combine null
            val account = wallet.sdkAccount.accountUuid
            if (signingAccount != null && signingAccount != account) return@combine null
            val current = prepared?.takeIf {
                it.attempt.proposal === proposal && it.attempt.account == account && it.attempt.approvalId == approvalId
            }
            val attempt = current?.attempt
            val phase = current?.phase ?: KeystoneQrPreparation.LOADING
            val ready = phase == KeystoneQrPreparation.READY
            SignKeystoneTransactionState(
                barTitle = stringRes(R.string.keystone_signWith_signTransaction),
                title = stringRes(R.string.keystone_signWith_title),
                subtitle = stringRes(R.string.keystone_signWith_desc),
                accountInfo =
                    ZashiAccountInfoListItemState(
                        icon = wallet.icon,
                        title = wallet.name,
                        subtitle =
                            if (proposal is ShieldTransactionProposal) {
                                stringRes("${wallet.transparentAddress.take(ADDRESS_MAX_LENGTH)}...")
                            } else {
                                stringRes("${wallet.unifiedAddress.take(ADDRESS_MAX_LENGTH)}...")
                            }
                    ),
                badgeText = stringRes(R.string.keystone_signWith_hardware),
                generateNextQrCode = { if (attempt != null && ready) generateNextQrCode(attempt) },
                qrData = current?.qrData,
                preparation = phase,
                retryButton = attempt?.takeIf { phase == KeystoneQrPreparation.ERROR }?.let {
                    ButtonState(
                        text = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                        onClick = { retryPreparation(it) }
                    )
                },
                positiveButton =
                    ButtonState(
                        text = stringRes(R.string.keystone_signWith_getSignature),
                        onClick = {
                            if (attempt != null && ready && isReady(attempt)) {
                                navigationRouter.forward(ScanKeystonePCZTRequest(attempt.approvalId))
                            }
                        },
                        isEnabled = ready
                    ),
                negativeButton =
                    ButtonState(
                        text = stringRes(R.string.keystone_signWith_reject),
                        onClick = { reject(attempt) }
                    ),
                secondaryButton =
                    ButtonState(
                        text = stringRes("Share PCZT"),
                        onClick = {
                            if (attempt != null && ready && isReady(attempt)) {
                                viewModelScope.launch {
                                    sharePCZT {
                                        navigationRouter.isForegroundAtEntry(sourceEntryId) && isReady(attempt)
                                    }
                                }
                            }
                        },
                        isEnabled = ready
                    ).takeIf { BuildConfig.DEBUG },
                onBack = { reject(attempt) },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT, replayExpiration = Duration.ZERO),
            initialValue = null
        )

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
        viewModelScope.launch {
            val selected = wallets.map { it.selectedKeystone() }.distinctUntilChangedBy { it?.sdkAccount?.accountUuid }
            combine(proposals, selected, retryRequests, approvalIds) { proposal, wallet, _, approvalId ->
                Triple(proposal, wallet, approvalId)
            }
                .collectLatest { (proposal, wallet, approvalId) ->
                    rejection.value = null
                    preparation.value = null
                    val account = wallet?.sdkAccount?.accountUuid ?: return@collectLatest
                    if (signingAccount == null) signingAccount = account
                    if (signingAccount != account || proposal == null) return@collectLatest
                    val approved = approvalId?.takeIf { it == originalApproval } ?: return@collectLatest
                    val attempt = Attempt(account, proposal, approved)
                    preparation.value = Preparation(attempt)
                    if (!isCurrent(attempt)) return@collectLatest
                    try {
                        val encoder = createKeystoneProposalPCZTEncoder()
                        currentCoroutineContext().ensureActive()
                        val part = checkNotNull(encoder.nextPart())
                        if (isCurrent(attempt)) {
                            preparation.value = Preparation(attempt, KeystoneQrPreparation.READY, encoder, part)
                        }
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (isCurrent(attempt)) preparation.value = Preparation(attempt, KeystoneQrPreparation.ERROR)
                    }
                }
        }
    }

    private fun retryPreparation(attempt: Attempt) {
        if (isCurrent(attempt) && preparation.value?.phase == KeystoneQrPreparation.ERROR) {
            preparation.value = Preparation(attempt)
            retryRequests.update { it + 1 }
        }
    }

    private fun generateNextQrCode(attempt: Attempt) {
        val current = preparation.value ?: return
        if (!isReady(attempt)) return
        try {
            val part = checkNotNull(current.encoder?.nextPart())
            if (isReady(attempt)) preparation.value = current.copy(qrData = part)
        } catch (_: Exception) {
            if (isCurrent(attempt)) preparation.value = Preparation(attempt, KeystoneQrPreparation.ERROR)
        }
    }

    private fun reject(attempt: Attempt?) {
        if (attempt != null && isCurrent(attempt) && preparation.value?.isRejecting == false) {
            rejection.value = attempt
        }
    }

    internal fun leaveSigning() = navigationRouter.back()

    private fun confirmRejection(attempt: Attempt) {
        if (rejection.value !== attempt || !isCurrent(attempt)) return
        rejection.value = null
        preparation.value = Preparation(attempt, isRejecting = true)
        viewModelScope.launch {
            delay(350.milliseconds)
            if (isCurrent(attempt)) {
                cancelKeystoneProposalFlow.rejectKeystone(attempt.proposal, attempt.account, sourceEntryId)
            }
        }
    }

    private fun closeRejection(attempt: Attempt) {
        if (rejection.value === attempt) rejection.value = null
    }

    private fun isCurrent(attempt: Attempt): Boolean =
        isActiveSigningAccount(attempt.account) && preparation.value?.attempt === attempt &&
            proposals.value === attempt.proposal &&
            keystoneProposalRepository.signingPreparationId() == attempt.approvalId &&
            (attempt.proposal !is ShieldTransactionProposal || attempt.proposal.approval?.isCurrent() == true)

    private fun isActiveSigningAccount(account: AccountUuid): Boolean =
        viewModelScope.isActive && wallets.value.selectedKeystone()?.sdkAccount?.accountUuid == account

    private fun isReady(attempt: Attempt): Boolean =
        isCurrent(attempt) && preparation.value?.phase == KeystoneQrPreparation.READY

    private fun List<WalletAccount>?.selectedKeystone(): KeystoneAccount? =
        this?.firstOrNull { it.isSelected } as? KeystoneAccount

    private class Attempt(
        val account: AccountUuid,
        val proposal: TransactionProposal,
        val approvalId: String,
    )

    private data class Preparation(
        val attempt: Attempt,
        val phase: KeystoneQrPreparation = KeystoneQrPreparation.LOADING,
        val encoder: UREncoder? = null,
        val qrData: String? = null,
        val isRejecting: Boolean = false,
    )
}
