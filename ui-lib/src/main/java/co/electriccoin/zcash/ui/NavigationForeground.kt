package co.electriccoin.zcash.ui

import kotlinx.coroutines.flow.first

fun NavigationRouter.isForegroundAtEntry(sourceEntryId: String?): Boolean =
    sourceEntryId == null || activeEntry.value?.let { it.id == sourceEntryId && it.hostResumed } == true

/** Waits for the owning entry after a system activity returns, or stops when that entry is replaced. */
suspend fun NavigationRouter.awaitForegroundAtEntry(sourceEntryId: String?): Boolean =
    sourceEntryId == null ||
        activeEntry.first { it == null || it.id != sourceEntryId || it.hostResumed }?.id == sourceEntryId
