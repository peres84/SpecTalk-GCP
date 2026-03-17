package com.spectalk.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spectalk.app.auth.AuthViewModel
import com.spectalk.app.ui.screens.HomeScreen
import com.spectalk.app.ui.screens.LoginScreen
import com.spectalk.app.ui.screens.RegisterScreen
import com.spectalk.app.ui.screens.SettingsScreen
import com.spectalk.app.ui.screens.SplashScreen
import com.spectalk.app.ui.screens.VoiceSessionScreen

@Composable
fun SpecTalkNavGraph() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val authState by authViewModel.state.collectAsStateWithLifecycle()

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
                }
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
                }
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
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                authViewModel = authViewModel,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToVoiceSession = { conversationId ->
                    // popUpTo(Home) ensures the stack never stacks two VoiceSession
                    // destinations (Home → VoiceSession → VoiceSession). Without this,
                    // both HomeScreen's LaunchedEffect and the existing ViewModel's
                    // wakeWordDetected collector can fire on the same wake event, pushing
                    // a second VoiceSessionScreen and opening a second WebSocket.
                    navController.navigate(Screen.VoiceSession.routeWith(conversationId)) {
                        popUpTo(Screen.Home.route)
                    }
                },
                onSignOut = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
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
