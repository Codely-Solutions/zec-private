package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.viewingKey
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.UUID

internal class GetVKUseCase(
    private val accounts: AccountDataSource,
    private val walletAccess: WalletWriteAccess,
) {
    private val review = MutableStateFlow<Review?>(null)
    val walletVersion = walletAccess.version

    fun availability(account: WalletAccount) =
        VKType.entries.associateWith { account.sdkAccount.viewingKey(it) != null }

    fun startReview(account: WalletAccount, generation: Long, type: VKType): String? {
        val owner = account.sdkAccount.accountUuid
        if (!isSelected(owner, generation)) return null
        val key = account.sdkAccount.viewingKey(type) ?: return null
        val id = UUID.randomUUID().toString()
        review.value = Review(id, owner, generation, type, key, approved = type == VKType.INCOMING)
        return id
    }

    fun isCurrent(id: String, type: VKType) = current(id, type) != null

    fun watchReview(id: String, type: VKType) =
        combine(review, accounts.allAccounts, walletVersion) { _, _, _ -> isCurrent(id, type) }.distinctUntilChanged()

    fun approveFullKey(id: String): Boolean {
        val pending = current(id, VKType.FULL)?.takeUnless { it.approved } ?: return false
        return review.compareAndSet(pending, pending.approve())
    }

    fun key(id: String, type: VKType): String? = current(id, type)?.takeIf { it.approved }?.key

    fun endReview(id: String) = review.update { it?.takeUnless { pending -> pending.id == id } }

    private fun current(id: String, type: VKType): Review? = review.value?.takeIf {
        it.id == id && it.type == type && isSelected(it.owner, it.generation)
    }

    private fun isSelected(owner: AccountUuid, generation: Long): Boolean {
        val selected = accounts.allAccounts.value?.firstOrNull { it.isSelected }
        return selected?.sdkAccount?.accountUuid == owner && walletAccess.permits(generation)
    }

    private class Review(
        val id: String,
        val owner: AccountUuid,
        val generation: Long,
        val type: VKType,
        val key: String,
        val approved: Boolean,
    ) {
        fun approve() = Review(id, owner, generation, type, key, approved = true)
    }
}
