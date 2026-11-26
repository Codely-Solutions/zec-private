package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.Synchronizer
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
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeystoneProposalRepositoryTest {
    private val accountDataSource = mockk<AccountDataSource> {
        every { signingAuthorityChanges } returns flowOf(Unit)
    }
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

    @Test
    fun reviewedMigrationRequiresOriginalProposalAndAuthorityAtPcztEntry() = runBlocking {
        val source = mockk<Proposal>()
        val original = repository.setMigrationSweepProposal(source)
        val owner = mockk<WalletAccount>(relaxed = true)
        var authorized = true
        coEvery { proposalDataSource.createPcztFromProposal(owner, source, any()) } coAnswers {
            authorized = false
            thirdArg<() -> Unit>()()
            error("Revoked review reached PCZT creation")
        }

        val staleApproval = assertFailsWith<IllegalStateException> {
            repository.prepareReviewedMigration(original, owner, { authorized }, { authorized })
        }
        assertEquals("The migration review changed during preparation.", staleApproval.message)
        assertNull(repository.getProposalPCZT())
        coVerify(exactly = 0) { proposalDataSource.addProofsToPczt(any(), any()) }

        val replacement = repository.setMigrationSweepProposal(mockk())
        assertFailsWith<IllegalStateException> {
            repository.prepareReviewedMigration(original, owner, { true }, { true })
        }
        assertEquals(replacement, repository.transactionProposal.value)
        coVerify(exactly = 1) { proposalDataSource.createPcztFromProposal(any(), any(), any()) }
    }

    /**
     * A multi-step PCZT rejection means "TEX is unsupported on Keystone" only when the proposal
     * actually pays a TEX address — the one flow where a multi-step proposal is the expected shape.
     */
    @Test
    fun aMultiStepPcztRejectionOfATexPaymentBecomesTexUnsupportedOnKeystone() =
        runBlocking {
            givenCurrentProposalPays(WalletAddress.Tex.new(RECIPIENT))
            coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } throws
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
            coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } throws
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
            coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } throws
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
        coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
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
        coEvery { proposalDataSource.createShieldProposal(approval) } returns ShieldTransactionProposal(mockk())
        coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
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

    @Test
    fun aRegularPreparationRetainsItsSelectedAccountApproval() = runBlocking {
        givenCurrentProposalPays(WalletAddress.Unified.new(RECIPIENT))
        val proposal = repository.getTransactionProposal()
        coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
        repository.createPCZTFromProposal()
        var permitted = true
        repository.approveForSigning(proposal, canSign = { permitted })
        assertNotNull(repository.signingOwner(proposal))

        permitted = false
        assertNull(repository.signingOwner(proposal))
        assertFailsWith<IllegalStateException> { repository.createPCZTEncoder() }
        coVerify(exactly = 0) { signer.generatePczt(any()) }
        repository.clear()
    }

    @Test
    fun retiringTheSynchronizerWithdrawsTheObservedApprovalWithoutReplacingTheDraft() = runBlocking {
        val transport = WalletTransportAccess()
        val synchronizer = mockk<Synchronizer>()
        every { accountDataSource.signingAuthorityChanges } returns transport.authorityChanges
        givenCurrentProposalPays(WalletAddress.Unified.new(RECIPIENT))
        val proposal = repository.getTransactionProposal()
        coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
        repository.createPCZTFromProposal()
        repository.approveForSigning(proposal, canSign = { !transport.isRetired(synchronizer) })
        val ready = CompletableDeferred<String>()
        val invalidated = async {
            repository.signingPreparationIds.onEach { id -> if (id != null) ready.complete(id) }.first { it == null }
        }

        withTimeout(2_000) {
            assertEquals(repository.signingPreparationId(), ready.await())
            transport.retire(synchronizer)
            assertNull(invalidated.await())
        }

        assertEquals(proposal, repository.transactionProposal.value)
        assertFailsWith<IllegalStateException> { repository.createPCZTEncoder() }
        coVerify(exactly = 0) { signer.generatePczt(any()) }
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
