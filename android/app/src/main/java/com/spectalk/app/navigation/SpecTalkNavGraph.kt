package com.spectalk.app.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.spectalk.app.auth.AuthUiState
import com.spectalk.app.auth.AuthViewModel
import com.spectalk.app.notifications.NotificationEventBus
import com.spectalk.app.settings.AppPreferences
import com.spectalk.app.ui.components.AppDrawer
import com.spectalk.app.ui.screens.GalleryScreen
import com.spectalk.app.ui.screens.HomeScreen
import com.spectalk.app.ui.screens.LoginScreen
import com.spectalk.app.ui.screens.RegisterScreen
import com.spectalk.app.ui.screens.SettingsScreen
import com.spectalk.app.ui.screens.SplashScreen
import com.spectalk.app.ui.screens.VoiceSessionScreen
import kotlinx.coroutines.launch

@Composable
fun SpecTalkNavGraph() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Track current route to control drawer visibility and highlight
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val drawerRoutes = setOf(Screen.Home.route, Screen.Gallery.route, Screen.Settings.route)
    val showDrawer = currentRoute in drawerRoutes

    fun openDrawer() = scope.launch { drawerState.open() }
    fun closeDrawer() = scope.launch { drawerState.close() }

    // Consume pending auto-open (background-killed case)
    LaunchedEffect(Unit) {
        AppPreferences.getPendingAutoOpenConversationId(context)?.let { id ->
            AppPreferences.clearPendingAutoOpenConversationId(context)
            navController.navigate(Screen.VoiceSession.routeWith(id)) {
                popUpTo(Screen.Home.route) { inclusive = false }
            }
        }
        // Foreground FCM tap
        NotificationEventBus.pendingConversationId.collect { conversationId ->
            navController.navigate(Screen.VoiceSession.routeWith(conversationId)) {
                popUpTo(Screen.Home.route)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showDrawer,
        drawerContent = {
            if (showDrawer) {
                val email = (authState as? AuthUiState.Authenticated)?.email ?: ""
                AppDrawer(
                    currentRoute = currentRoute,
                    userEmail = email,
                    onNavigateToHome = {
                        closeDrawer()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToGallery = {
                        closeDrawer()
                        navController.navigate(Screen.Gallery.route) {
                            popUpTo(Screen.Home.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSettings = {
                        closeDrawer()
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    },
                    onSignOut = {
                        closeDrawer()
                        authViewModel.signOut()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = Screen.Splash.route) {

            composable(Screen.Splash.route) {
                SplashScreen(
                    authState = authState,
                    onNavigateToHome = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    },
                    onNavigateToLogin = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    authViewModel = authViewModel,
                    onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                    onNavigateToHome = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Screen.Register.route) {
                RegisterScreen(
                    authViewModel = authViewModel,
                    onNavigateToLogin = { navController.popBackStack() },
                    onRegistrationComplete = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Register.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    authViewModel = authViewModel,
                    onOpenDrawer = { openDrawer() },
                    onNavigateToVoiceSession = { conversationId ->
                        navController.navigate(Screen.VoiceSession.routeWith(conversationId)) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                )
            }

            composable(Screen.Gallery.route) {
                GalleryScreen(
                    onOpenDrawer = { openDrawer() },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSignOut = {
                        authViewModel.signOut()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(
                route = Screen.VoiceSession.route,
                arguments = listOf(
                    navArgument("conversationId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId")
                VoiceSessionScreen(
                    conversationId = conversationId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
