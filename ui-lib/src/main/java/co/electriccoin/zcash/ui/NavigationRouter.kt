package co.electriccoin.zcash.ui

import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

interface NavigationRouter {
    val activeEntry: StateFlow<ActiveNavigationEntry?> get() = noActiveEntry

    fun registerEntryObserver(): ActiveNavigationEntryObserver = object : ActiveNavigationEntryObserver {
        override fun update(entry: ActiveNavigationEntry?) = Unit
        override fun close() = Unit
    }

    /**
     * Add [routes] to backstack.
     */
    fun forward(vararg routes: Any)

    /**
     * Replace current screen by the first route and add the rest of [routes] to backstack.
     */
    fun replace(vararg routes: Any)

    /**
     * Pop all screens except for the root and add [routes] to backstack.
     */
    fun replaceAll(vararg routes: Any)

    /**
     * Pop every screen back to and including the first [route] found on the backstack, then add [routes] to
     * backstack - the first of them takes the popped screen's place.
     */
    fun replaceFrom(route: KClass<*>, vararg routes: Any)

    /**
     * Pop last screen from backstack.
     */
    fun back()

    fun backTo(route: KClass<*>)

    fun custom(block: (NavBackStackEntry?) -> NavigationCommand?)

    /**
     * Pop all screens from backstack except for the root.
     */
    fun backToRoot()

    fun observePipeline(): Flow<BaseNavigationCommand>
}

data class ActiveNavigationEntry(val id: String, val route: String?)

interface ActiveNavigationEntryObserver {
    fun update(entry: ActiveNavigationEntry?)
    fun close()
}

private val noActiveEntry = MutableStateFlow<ActiveNavigationEntry?>(null).asStateFlow()

internal fun NavigationRouter.forwardFrom(
    sourceEntryId: String?,
    isStillAllowed: () -> Boolean,
    buildRoute: () -> Any?,
) {
    if (!isStillAllowed()) return
    if (sourceEntryId == null) {
        buildRoute()?.let { forward(it) }
    } else {
        custom { current ->
            if (current?.id == sourceEntryId && isStillAllowed()) {
                buildRoute()?.let { NavigationCommand.Forward(listOf(it), sourceEntryId, isStillAllowed) }
            } else {
                null
            }
        }
    }
}

@Suppress("TooManyFunctions") // The router implements the individual navigation commands.
class NavigationRouterImpl : NavigationRouter {
    private var job: Job? = null

    private var lastNavCommand: BaseNavigationCommand? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val channel = Channel<BaseNavigationCommand>()
    private val entryState = MutableStateFlow<ActiveNavigationEntry?>(null)
    private var entryObserver: ActiveNavigationEntryObserver? = null

    override val activeEntry = entryState.asStateFlow()

    override fun registerEntryObserver(): ActiveNavigationEntryObserver =
        object : ActiveNavigationEntryObserver {
            override fun update(entry: ActiveNavigationEntry?) {
                if (entryObserver === this) entryState.value = entry
            }

            override fun close() {
                if (entryObserver === this) {
                    entryObserver = null
                    entryState.value = null
                }
            }
        }.also {
            entryObserver = it
            entryState.value = null
        }

    override fun forward(vararg routes: Any) = navigateWithBackoff(NavigationCommand.Forward(routes.toList()))

    override fun replace(vararg routes: Any) = navigateWithBackoff(NavigationCommand.Replace(routes.toList()))

    override fun replaceAll(vararg routes: Any) = navigateWithBackoff(NavigationCommand.ReplaceAll(routes.toList()))

    override fun replaceFrom(route: KClass<*>, vararg routes: Any) =
        navigateWithBackoff(NavigationCommand.ReplaceFrom(route, routes.toList()))

    override fun back() = navigateWithBackoff(NavigationCommand.Back)

    override fun backTo(route: KClass<*>) = navigateWithBackoff(NavigationCommand.BackTo(route))

    override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) =
        navigateWithBackoff(CustomNavigationCommand(block))

    override fun backToRoot() = navigateWithBackoff(NavigationCommand.BackToRoot)

    override fun observePipeline() = channel.receiveAsFlow()

    private fun navigateWithBackoff(command: BaseNavigationCommand) {
        if (job?.isActive == true && command == lastNavCommand) {
            return // skip if already running
        }
        lastNavCommand = command
        job =
            scope.launch {
                channel.trySend(command)
                delay(.5.seconds) // backoff
            }
    }
}

sealed interface BaseNavigationCommand

data class CustomNavigationCommand(
    val block: (current: NavBackStackEntry?) -> NavigationCommand?
) : BaseNavigationCommand

sealed interface NavigationCommand : BaseNavigationCommand {
    data class Forward(
        val routes: List<Any>,
        val sourceEntryId: String? = null,
        val isStillAllowed: (() -> Boolean)? = null,
    ) : NavigationCommand

    data class Replace(
        val routes: List<Any>
    ) : NavigationCommand

    data class ReplaceAll(
        val routes: List<Any>
    ) : NavigationCommand

    data class ReplaceFrom(
        val route: KClass<*>,
        val routes: List<Any>
    ) : NavigationCommand

    data object Back : NavigationCommand

    data class BackTo(
        val route: KClass<*>
    ) : NavigationCommand

    data object BackToRoot : NavigationCommand
}
