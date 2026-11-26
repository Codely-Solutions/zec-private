package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Allows the retained Sending screen to act on the exact foreground retry that opened it. */
class MigrationSendAuthorization {
    private val pending = AtomicReference<Request?>(null)

    fun issue(target: MigrationSendingArgs, permits: () -> Boolean): MigrationSendingArgs {
        val scoped = target.copy(authorizationId = UUID.randomUUID().toString())
        pending.set(Request(scoped, permits))
        return scoped
    }

    fun take(target: MigrationSendingArgs): (() -> Boolean)? {
        val request = pending.get() ?: return null
        return if (request.target == target && pending.compareAndSet(request, null)) request.permits else null
    }

    private class Request(val target: MigrationSendingArgs, val permits: () -> Boolean)
}
