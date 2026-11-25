package co.electriccoin.zcash.ui.screen.send

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendFiatProposalTest {
    @Test
    fun allSpendingAccountsRejectSubgridFiatBeforeTheirProposalPortsAndCanRetry() = runTest {
        val accounts = listOf(EditAccountFixtures.zodl(), EditAccountFixtures.keystone(), EditAccountFixtures.ledger())
        accounts.forEach { account ->
            val proposals = mockk<ZashiProposalRepository>(relaxed = true)
            val keystone = mockk<KeystoneProposalRepository>(relaxed = true)
            val ledger = mockk<LedgerProposalPipeline>(relaxed = true)
            val createProposal = CreateProposalUseCase(
                keystoneProposalRepository = keystone,
                ledgerProposalPipeline = ledger,
                zashiProposalRepository = proposals,
                accountDataSource = mockk<AccountDataSource> {
                    coEvery { getSelectedAccount() } returns account
                },
                navigationRouter = mockk(relaxed = true),
            )
            val send = ZecSend(
                destination = WalletAddress.Unified.new("synthetic-recipient"),
                amount = Zatoshi(4_999),
                memo = Memo(""),
                proposal = null,
            )
            assertFailsWith<IllegalArgumentException> { createProposal.prepare(send, floor = true) }
            coVerify(exactly = 0) { proposals.createProposal(any()) }
            coVerify(exactly = 0) { keystone.createProposal(any()) }
            coVerify(exactly = 0) { ledger.createProposal(any()) }

            val expected = send.copy(amount = Zatoshi(5_000))
            createProposal.prepare(expected, floor = true)
            createProposal.prepare(send.copy(amount = Zatoshi(5_001)), floor = true)
            when (account) {
                is ZodlAccount -> coVerify(exactly = 2) { proposals.createProposal(expected) }
                is KeystoneAccount -> coVerify(exactly = 2) { keystone.createProposal(expected) }
                is LedgerAccount -> coVerify(exactly = 2) { ledger.createProposal(expected) }
                else -> error("Unexpected test account")
            }
        }
    }

    @Test
    fun explicitZeroRequiresZecInputShieldedRecipientAndMemo() = runTest {
        val proposals = mockk<ZashiProposalRepository>(relaxed = true)
        val createProposal = softwareProposal(proposals)

        val send = ZecSend(
            destination = WalletAddress.Unified.new("synthetic-recipient"),
            amount = Zatoshi(0),
            memo = Memo("Memo only"),
            proposal = null,
        )
        assertFailsWith<IllegalArgumentException> { createProposal.prepare(send, floor = true) }
        assertFailsWith<IllegalArgumentException> { createProposal.prepare(send.copy(memo = Memo("")), floor = false) }
        assertFailsWith<IllegalArgumentException> {
            val tex = send.copy(destination = WalletAddress.Tex.new("synthetic-tex-recipient"))
            createProposal.prepare(tex, floor = false)
        }
        createProposal.prepare(send, floor = false)
        coVerify(exactly = 1) { proposals.createProposal(any()) }
        coVerify(exactly = 1) { proposals.createProposal(send) }
        assertEquals(Zatoshi(0), send.amount)
    }

    @Test
    fun proposalPayloadIncludesMemoOnlyForShieldedRecipients() = runTest {
        val proposals = mockk<ZashiProposalRepository>(relaxed = true)
        val createProposal = softwareProposal(proposals)
        val memo = Memo("Draft memo")
        val send = ZecSend(
            destination = WalletAddress.Unified.new("synthetic-unified-recipient"),
            amount = Zatoshi(5_000),
            memo = memo,
            proposal = null,
        )
        val sapling = send.copy(destination = WalletAddress.Sapling.new("synthetic-sapling-recipient"))
        val transparent = send.copy(destination = WalletAddress.Transparent.new("synthetic-transparent-recipient"))
        val tex = send.copy(destination = WalletAddress.Tex.new("synthetic-tex-recipient"))

        listOf(send, sapling, transparent, tex).forEach { createProposal.prepare(it, floor = false) }

        coVerify(exactly = 4) { proposals.createProposal(any()) }
        coVerify(exactly = 1) { proposals.createProposal(send) }
        coVerify(exactly = 1) { proposals.createProposal(sapling) }
        coVerify(exactly = 1) { proposals.createProposal(transparent.copy(memo = Memo(""))) }
        coVerify(exactly = 1) { proposals.createProposal(tex.copy(memo = Memo(""))) }
    }

    @Test
    fun proposalBoundaryChecksSupplyBeforeApplyingFiatFloor() = runTest {
        val proposals = mockk<ZashiProposalRepository>(relaxed = true)
        val createProposal = softwareProposal(proposals)
        val maxSend = ZecSend(
            destination = WalletAddress.Unified.new("synthetic-recipient"),
            amount = Zatoshi(2_100_000_000_000_000),
            memo = Memo(""),
            proposal = null,
        )
        // The SDK constructor rejects this amount, so use a stand-in to exercise our port guard.
        val beyondSupply = mockk<Zatoshi> { every { value } returns 2_100_000_000_000_001L }
        listOf(false, true).forEach { fiat ->
            assertFailsWith<IllegalArgumentException> {
                createProposal.prepare(maxSend.copy(amount = beyondSupply), floor = fiat)
            }
            createProposal.prepare(maxSend, floor = fiat)
        }
        coVerify(exactly = 2) { proposals.createProposal(any()) }
        coVerify(exactly = 2) { proposals.createProposal(maxSend) }
    }

    private suspend fun CreateProposalUseCase.prepare(send: ZecSend, floor: Boolean) =
        invoke(send, floor, "fixture-send") { true }

    private fun softwareProposal(proposals: ZashiProposalRepository) = CreateProposalUseCase(
        keystoneProposalRepository = mockk(relaxed = true),
        ledgerProposalPipeline = mockk(relaxed = true),
        zashiProposalRepository = proposals,
        accountDataSource = mockk<AccountDataSource> {
            coEvery { getSelectedAccount() } returns EditAccountFixtures.zodl()
        },
        navigationRouter = mockk(relaxed = true),
    )
}
