package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.LedgerProposalRepository

class StartLedgerSigningUseCase(
    private val ledgerProposalRepository: LedgerProposalRepository,
    private val submitLedgerProposal: SubmitLedgerProposalUseCase,
) {
    operator fun invoke(sourceEntryId: String? = null) = ledgerProposalRepository.startSigning { signed ->
        submitLedgerProposal(signed, sourceEntryId)
    }
}
