package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.app.Activity
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.serialization.generateHashCode
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.design.KeyboardManager
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.about.util.WebBrowserUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

interface Navigator {
    suspend fun executeCommand(command: NavigationCommand)

    suspend fun executeCommand(command: CustomNavigationCommand)
}

@Suppress("TooManyFunctions")
class NavigatorImpl(
    private val activity: Activity,
    private val navController: NavHostController,
    private val keyboardManager: KeyboardManager,
    private val applicationStateProvider: ApplicationStateProvider,
    private val browserAppearance: () -> Pair<AppearanceMode, Boolean> = { AppearanceMode.SYSTEM to false },
    private val canCommitOwnedNavigation: () -> Boolean = { true },
) : Navigator {
    private val commandLock = Mutex()

    override suspend fun executeCommand(command: NavigationCommand) = commandLock.withLock {
        executeOne(command)
    }

    private suspend fun executeOne(command: NavigationCommand) {
        when {
            command is NavigationCommand.ForEntry && canCommitOwnedNavigation() ->
                keyboardManager.controller?.hide()
            command is NavigationCommand.ForEntry -> Unit
            else -> keyboardManager.close()
        }
        executeAfterKeyboard(command)
    }

    internal suspend fun executeLaunchCommand(
        command: NavigationCommand,
        canNavigate: () -> Boolean,
    ): Boolean = commandLock.withLock {
        keyboardManager.close()
        if (!canNavigate()) return@withLock false
        val previous = navController.currentBackStackEntry
        executeAfterKeyboard(command)
        navController.currentBackStackEntry !== previous
    }

    private suspend fun executeAfterKeyboard(command: NavigationCommand) {
        when (command) {
            is NavigationCommand.ForEntry -> executeOwned(command)
            is NavigationCommand.Forward -> forward(command)
            is NavigationCommand.Replace -> replace(command)
            is NavigationCommand.ReplaceAll -> replaceAll(command)
            is NavigationCommand.ReplaceFrom -> replaceFrom(command)
            NavigationCommand.Back -> navController.popBackStack()
            is NavigationCommand.BackTo -> backTo(command)
            NavigationCommand.BackToRoot -> backToRoot()
        }
    }

    private suspend fun executeOwned(command: NavigationCommand.ForEntry) {
        val previous = navController.currentBackStackEntry
        if (previous?.id == command.sourceEntryId && canCommitOwnedNavigation() && command.isStillAllowed()) {
            command.buildCommand()?.let { executeAfterKeyboard(it) }
        }
        command.acknowledge(navController.currentBackStackEntry !== previous)
    }

    override suspend fun executeCommand(command: CustomNavigationCommand) = commandLock.withLock {
        command.block(navController.currentBackStackEntry)?.let { executeOne(it) }
        Unit
    }

    @SuppressLint("RestrictedApi")
    @OptIn(InternalSerializationApi::class)
    private fun backTo(command: NavigationCommand.BackTo) {
        navController.popBackStack(
            destinationId = command.route.serializer().generateHashCode(),
            inclusive = false
        )
    }

    private fun backToRoot() {
        navController.currentDestination?.parent?.startDestinationId?.let {
            navController.popBackStack(
                destinationId = it,
                inclusive = false
            )
        }
    }

    private suspend fun replaceAll(command: NavigationCommand.ReplaceAll) {
        command.routes.forEachIndexed { index, route ->
            when (route) {

                is ExternalUrl -> {
                    if (index == 0) {
                        navController.currentDestination?.parent?.startDestinationId?.let {
                            navController.popBackStack(
                                route = it,
                                inclusive = false
                            )
                        }
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("External url can be opened as last screen only")
                    }

                    startWebBrowser(route)
                }

                else -> {
                    navController.executeNavigation(route = route) {
                        if (index == 0) {
                            navController.currentDestination?.parent?.startDestinationId?.let {
                                popUpTo(it) {
                                    inclusive = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @OptIn(InternalSerializationApi::class)
    private suspend fun replaceFrom(command: NavigationCommand.ReplaceFrom) {
        val popUpToId = command.route.serializer().generateHashCode()
        command.routes.forEachIndexed { index, route ->
            when (route) {

                is ExternalUrl -> {
                    if (index == 0) {
                        navController.popBackStack(destinationId = popUpToId, inclusive = true)
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("External url can be opened as last screen only")
                    }

                    startWebBrowser(route)
                }

                else -> {
                    navController.executeNavigation(route = route) {
                        if (index == 0) {
                            popUpTo(popUpToId) {
                                inclusive = true
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun replace(command: NavigationCommand.Replace) {
        command.routes.forEachIndexed { index, route ->
            when (route) {

                is ExternalUrl -> {
                    if (index == 0) {
                        navController.popBackStack()
                    }

                    if (index != command.routes.lastIndex) {
                        throw UnsupportedOperationException("External url can be opened as last screen only")
                    }

                    startWebBrowser(route)
                }

                else -> {
                    navController.executeNavigation(route = route) {
                        if (index == 0) {
                            popUpTo(navController.currentBackStackEntry?.destination?.id ?: 0) {
                                inclusive = true
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun forward(command: NavigationCommand.Forward) {
        command.routes.forEach { route ->
            when (route) {
                is ExternalUrl -> startWebBrowser(route)
                else -> navController.executeNavigation(route = route)
            }
        }
    }

    private fun startWebBrowser(route: ExternalUrl) {
        val (appearanceMode, isOledEnabled) = browserAppearance()
        val launched = WebBrowserUtil.startActivity(
            activity = activity,
            url = route.url,
            appearanceMode = appearanceMode,
            isOledEnabled = isOledEnabled
        )
        if (launched) applicationStateProvider.onThirdPartyUiShown()
    }

    private fun NavHostController.executeNavigation(
        route: Any,
        builder: (NavOptionsBuilder.() -> Unit)? = null
    ) {
        if (route is String) {
            if (builder == null) {
                navigate(route)
            } else {
                navigate(route, builder)
            }
        } else {
            if (builder == null) {
                navigate(route)
            } else {
                navigate(route, builder)
            }
        }
    }

}
