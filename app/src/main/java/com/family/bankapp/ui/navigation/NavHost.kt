package com.family.bankapp.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.family.bankapp.update.AppUpdateInstaller
import com.family.bankapp.ui.viewmodel.AppUpdateUiState
import com.family.bankapp.ui.viewmodel.AppUpdateViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.family.bankapp.ui.screens.BankDetailScreen
import com.family.bankapp.ui.screens.BillEditScreen
import com.family.bankapp.ui.screens.BillsScreen
import com.family.bankapp.ui.screens.BanksScreen
import com.family.bankapp.ui.screens.DashboardScreen
import com.family.bankapp.ui.screens.PrivacyPolicyScreen
import com.family.bankapp.ui.screens.SettingsScreen
import android.content.Context
import android.content.ContextWrapper

private tailrec fun Context.findComponentActivity(): ComponentActivity {
    when (this) {
        is ComponentActivity -> return this
        is ContextWrapper -> return baseContext.findComponentActivity()
        else -> error("Activity not found")
    }
}

sealed class Screen(val route: String, val label: String) {
    data object Dashboard : Screen("dashboard", "Home")
    data object Banks : Screen("banks", "Banks")
    data object Bills : Screen("bills", "Bills")
    data object Settings : Screen("settings", "Settings")
    data object PrivacyPolicy : Screen("privacy-policy", "Privacy")
    data object BankDetail : Screen("bank/{bankId}", "Bank") {
        fun createRoute(bankId: Long) = "bank/$bankId"
    }
    data object BillEdit : Screen("bill/edit?billId={billId}", "Bill") {
        fun createRoute(billId: Long? = null) = if (billId == null) "bill/edit" else "bill/edit?billId=$billId"
    }
}

private val bottomTabs = listOf(
    Screen.Dashboard to Icons.Default.Home,
    Screen.Banks to Icons.Default.AccountBalance,
    Screen.Bills to Icons.Default.Receipt,
    Screen.Settings to Icons.Default.Settings
)

@Composable
fun BankAppNavHost() {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val updateVm: AppUpdateViewModel = viewModel(activity)
    val updateState by updateVm.state.collectAsState()

    var lastAutoInstallAttempt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(updateState) {
        val ready = updateState as? AppUpdateUiState.ReadyToInstall ?: return@LaunchedEffect
        if (ready.installAttempt == lastAutoInstallAttempt) return@LaunchedEffect
        lastAutoInstallAttempt = ready.installAttempt
        kotlinx.coroutines.delay(350)
        AppUpdateInstaller.startInstall(context, ready.apkFile)
            .onFailure { error ->
                updateVm.onInstallLaunchFailed(
                    error.message ?: "Could not open installer — tap Open installer"
                )
            }
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in bottomTabs.map { it.first.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { (screen, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = screen.label) },
                            label = { Text(screen.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(padding = androidx.compose.foundation.layout.PaddingValues())
            }
            composable(Screen.Banks.route) {
                BanksScreen(
                    padding = androidx.compose.foundation.layout.PaddingValues(),
                    onOpenBank = { id ->
                        navController.navigate(Screen.BankDetail.createRoute(id))
                    }
                )
            }
            composable(
                route = Screen.BankDetail.route,
                arguments = listOf(navArgument("bankId") { type = NavType.LongType })
            ) { entry ->
                val bankId = entry.arguments?.getLong("bankId") ?: return@composable
                BankDetailScreen(
                    bankId = bankId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Bills.route) {
                BillsScreen(
                    padding = androidx.compose.foundation.layout.PaddingValues(),
                    onAddBill = { navController.navigate(Screen.BillEdit.createRoute()) },
                    onEditBill = { id -> navController.navigate(Screen.BillEdit.createRoute(id)) }
                )
            }
            composable(
                route = "bill/edit?billId={billId}",
                arguments = listOf(
                    navArgument("billId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) { entry ->
                val rawId = entry.arguments?.getLong("billId") ?: -1L
                BillEditScreen(
                    billId = if (rawId < 0) null else rawId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    padding = androidx.compose.foundation.layout.PaddingValues(),
                    onOpenPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) },
                    updateVm = updateVm
                )
            }
            composable(Screen.PrivacyPolicy.route) {
                PrivacyPolicyScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
