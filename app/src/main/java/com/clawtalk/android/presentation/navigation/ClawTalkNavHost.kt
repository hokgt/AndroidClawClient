package com.clawtalk.android.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clawtalk.android.presentation.agentlist.AgentListScreen
import com.clawtalk.android.presentation.chat.ChatScreen
import com.clawtalk.android.presentation.settings.SettingsScreen
import com.clawtalk.android.presentation.settings.SettingsViewModel

@Composable
fun ClawTalkNavHost(
    navController: NavHostController = rememberNavController(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val isConfigured by settingsViewModel.isConfigured.collectAsState(initial = false)

    val startDestination = if (isConfigured) {
        Screen.AgentList.route
    } else {
        Screen.Settings.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.AgentList.route) {
            AgentListScreen(
                onAgentClick = { agentId ->
                    navController.navigate(Screen.Chat.createRoute(agentId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            ChatScreen(
                agentId = agentId,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.AgentList.route) {
                            popUpTo(Screen.Settings.route) { inclusive = true }
                        }
                    }
                },
                onSettingsSaved = {
                    navController.navigate(Screen.AgentList.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

sealed class Screen(val route: String) {
    object AgentList : Screen("agents")
    object Settings : Screen("settings")
    object Chat : Screen("chat/{agentId}") {
        fun createRoute(agentId: String) = "chat/$agentId"
    }
}
