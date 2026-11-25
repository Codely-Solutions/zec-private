package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeystoneProposalRepositoryTest {
    private val accountDataSource = mockk<AccountDataSource>()
    private val proposalDataSource = mockk<ProposalDataSource>()
    private val signer = mockk<KeystoneSDKProvider>()
    private val appAccess = AppAccessSession()

    private val repository: KeystoneProposalRepository =
        KeystoneProposalRepositoryImpl(
            accountDataSource = accountDataSource,
            proposalDataSource = proposalDataSource,
            keystoneSDKProvider = signer,
            processSwapTransaction = mockk(relaxed = true),
            appAccessSession = appAccess,
        )

    @Test
    fun setMigrationSweepProposalPublishesAMigrationSweepTransactionProposal() {
        val proposal = mockk<Proposal>()

        repository.setMigrationSweepProposal(proposal)

        val stored = repository.transactionProposal.value
        assertEquals(MigrationSweepTransactionProposal(proposal), stored)
    }

    /**
     * A multi-step PCZT rejection means "TEX is unsupported on Keystone" only when the proposal
     * actually pays a TEX address — the one flow where a multi-step proposal is the expected shape.
     */
    @Test
    fun aMultiStepPcztRejectionOfATexPaymentBecomesTexUnsupportedOnKeystone() =
        runBlocking {
            givenCurrentProposalPays(WalletAddress.Tex.new(RECIPIENT))
            coEvery { proposalDataSource.createPcztFromProposal(any(), any()) } throws
                PcztException.MultiStepProposalUnsupportedException()

            assertFailsWith<TexUnsupportedOnKSException> {
                repository.createPCZTFromProposal()
            }

            Unit
        }

    @Test
    fun aMultiStepPcztRejectionOfANonTexPaymentPropagatesUntouched() =
        runBlocking {
            givenCurrentProposalPays(WalletAddress.Unified.new(RECIPIENT))
            coEvery { proposalDataSource.createPcztFromProposal(any(), any()) } throws
                PcztException.MultiStepProposalUnsupportedException()

            assertFailsWith<PcztException.MultiStepProposalUnsupportedException> {
                repository.createPCZTFromProposal()
            }

            Unit
        }

    /**
     * Migration sweeps have no payment destination at all, so their multi-step rejection must never
     * be dressed up as a TEX error.
     */
    @Test
    fun aMultiStepPcztRejectionOfAMigrationSweepPropagatesUntouched() =
        runBlocking {
            repository.setMigrationSweepProposal(mockk<Proposal>())
            coEvery { accountDataSource.getSelectedAccount() } returns mockk(relaxed = true)
            coEvery { proposalDataSource.createPcztFromProposal(any(), any()) } throws
                PcztException.MultiStepProposalUnsupportedException()

            assertFailsWith<PcztException.MultiStepProposalUnsupportedException> {
                repository.createPCZTFromProposal()
            }

            Unit
        }

    @Test
    fun replacementDuringRedactionCannotBeHandedToKeystone() = runBlocking {
        givenCurrentProposalPays(WalletAddress.Unified.new(RECIPIENT))
        val approved = repository.getTransactionProposal()
        coEvery { proposalDataSource.createPcztFromProposal(any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any()) } returns Pczt(byteArrayOf(2))
        repository.createPCZTFromProposal()
        repository.approveForSigning(approved)
        val redactionStarted = CompletableDeferred<Unit>()
        val finishRedaction = CompletableDeferred<Unit>()
        coEvery { proposalDataSource.redactPcztForSigner(any()) } coAnswers {
            redactionStarted.complete(Unit)
            finishRedaction.await()
            Pczt(byteArrayOf(3))
        }

        val encoding = async { runCatching { repository.createPCZTEncoder() } }
        redactionStarted.await()
        givenCurrentProposalPays(WalletAddress.Unified.new("replacement"))
        finishRedaction.complete(Unit)

        assertIs<IllegalStateException>(encoding.await().exceptionOrNull())
        coVerify(exactly = 0) { signer.generatePczt(any()) }
        repository.createPCZTFromProposal()
        assertFailsWith<IllegalStateException> { repository.createPCZTEncoder() }
        Unit
    }

    @Test
    fun aRevokedShieldGrantEndsTheKeystonePreparation() = runBlocking {
        appAccess.unlock()
        val grant = appAccess.grant.value
        val account = mockk<WalletAccount>(relaxed = true)
        val approval = ShieldingApproval(account) { appAccess.permits(grant) }
        coEvery { proposalDataSource.createShieldProposal(account) } returns ShieldTransactionProposal(mockk())
        coEvery { proposalDataSource.createPcztFromProposal(any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any()) } returns Pczt(byteArrayOf(2))
        val proposal = repository.createShieldProposal(approval)
        repository.createPCZTFromProposal(account)
        repository.approveForSigning(proposal)
        assertNotNull(repository.signingPreparationIds.first())

        appAccess.lock()
        appAccess.unlock()
        assertNull(repository.signingPreparationIds.first())
        assertFailsWith<IllegalStateException> { repository.createPCZTEncoder() }
        coVerify(exactly = 0) { signer.generatePczt(any()) }
        repository.clear()
    }

    private suspend fun givenCurrentProposalPays(destination: WalletAddress) {
        coEvery { accountDataSource.getSelectedAccount() } returns mockk(relaxed = true)
        coEvery { proposalDataSource.createProposal(any(), any()) } returns
            RegularTransactionProposal(
                destination = destination,
                amount = Zatoshi(1234L),
                memo = Memo(""),
                proposal = mockk<Proposal>()
            )
        repository.createProposal(
            ZecSend(
                destination = destination,
                amount = Zatoshi(1234L),
                memo = Memo(""),
                proposal = null
            )
        )
    }

    private companion object {
        const val RECIPIENT = "recipient"
    }
}
