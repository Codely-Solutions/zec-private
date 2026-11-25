package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

internal fun paymentReviewFixture(
    accounts: AccountDataSource,
    router: NavigationRouter,
    software: ZashiProposalRepository,
    keystone: KeystoneProposalRepository = mockk(relaxed = true),
    ledger: LedgerProposalPipeline = mockk(relaxed = true),
    wallet: WalletWriteAccess = WalletWriteAccess(),
    reviews: ReviewedPaymentStore = ReviewedPaymentStore(accounts, wallet),
) = PreparePaymentReviewUseCase(
    accounts, AppAccessSession().apply { unlock() }, wallet, router, software, keystone, ledger, reviews,
)

internal fun selectedPaymentAccount(account: WalletAccount): AccountDataSource = mockk {
    every { allAccounts } returns MutableStateFlow(listOf(account))
}

internal fun paymentTestRouter(entryId: String) = mockk<NavigationRouter>(relaxed = true) {
    every { activeEntry } returns MutableStateFlow(ActiveNavigationEntry(entryId, null))
    every { custom(any()) } answers {
        val entry = mockk<NavBackStackEntry> { every { id } returns entryId }
        val command = firstArg<(NavBackStackEntry?) -> NavigationCommand?>()(entry)
        (command as? NavigationCommand.ForEntry)?.acknowledge(false)
    }
}
