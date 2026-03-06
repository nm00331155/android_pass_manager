package com.securevault.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securevault.app.ui.screen.addedit.AddEditScreen
import com.securevault.app.ui.screen.auth.AuthScreen
import com.securevault.app.ui.screen.backup.BackupScreen
import com.securevault.app.ui.screen.detail.DetailScreen
import com.securevault.app.ui.screen.generator.PasswordGeneratorScreen
import com.securevault.app.ui.screen.home.HomeScreen
import com.securevault.app.ui.screen.settings.SettingsScreen

@Composable
fun SecureVaultNavGraph(
    navGuardViewModel: NavGuardViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val isLocked by navGuardViewModel.isLocked.collectAsStateWithLifecycle()

    LaunchedEffect(isLocked, currentRoute) {
        if (isLocked && currentRoute != NavRoutes.Auth) {
            navController.navigate(NavRoutes.Auth) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.Auth
    ) {
        composable(NavRoutes.Auth) {
            AuthScreen(
                onAuthenticated = {
                    navController.navigate(NavRoutes.Home) {
                        popUpTo(NavRoutes.Auth) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.Home) {
            HomeScreen(
                onAddClick = { navController.navigate(NavRoutes.addEdit()) },
                onSettingsClick = { navController.navigate(NavRoutes.Settings) },
                onGeneratorClick = { navController.navigate(NavRoutes.Generator) },
                onDetailClick = { navController.navigate(NavRoutes.detail(it)) }
            )
        }

        composable(
            route = NavRoutes.AddEditPattern,
            arguments = listOf(navArgument("credentialId") { type = NavType.LongType })
        ) {
            AddEditScreen(
                onNavigateBack = { navController.popBackStack() },
                onGeneratorClick = { navController.navigate(NavRoutes.Generator) }
            )
        }

        composable(
            route = NavRoutes.DetailPattern,
            arguments = listOf(navArgument("credentialId") { type = NavType.LongType })
        ) { backStackEntry ->
            DetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditClick = { id ->
                    navController.navigate(NavRoutes.addEdit(id))
                }
            )
        }

        composable(NavRoutes.Settings) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBackup = { navController.navigate(NavRoutes.Backup) }
            )
        }

        composable(NavRoutes.Backup) {
            BackupScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.Generator) {
            PasswordGeneratorScreen(
                onNavigateBack = { navController.popBackStack() },
                onUsePassword = { generatedPassword ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("generated_password", generatedPassword)
                    navController.popBackStack()
                }
            )
        }
    }
}
