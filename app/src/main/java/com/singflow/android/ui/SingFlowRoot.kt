package com.singflow.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.singflow.android.ui.components.ImportBottomSheet
import com.singflow.android.ui.screens.AppRoutingScreen
import com.singflow.android.ui.screens.DashboardScreen
import com.singflow.android.ui.screens.DiagnosticsScreen
import com.singflow.android.ui.screens.ExportBackupScreen
import com.singflow.android.ui.screens.LogsScreen
import com.singflow.android.ui.screens.NodesScreen
import com.singflow.android.ui.screens.SettingsScreen
import com.singflow.android.ui.screens.SubscriptionsScreen
import kotlinx.coroutines.launch

/**
 * 应用根 Composable：底部导航 + 四个核心页面 + 导入弹层。
 */
@Composable
fun SingFlowRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selectedNode by viewModel.selectedNode.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val configPreview by viewModel.configPreview.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val appRouteRules by viewModel.appRouteRules.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val geoScanning by viewModel.geoScanning.collectAsStateWithLifecycle()

    var showImportSheet by remember { mutableStateOf(false) }

    val countries = remember(nodes) {
        nodes.filter { it.countryCode.length == 2 && it.countryCode.uppercase() != "UN" }
            .distinctBy { it.countryCode.uppercase() }
            .map { it.countryCode.uppercase() to it.countryNameCn }
            .sortedBy { it.second }
    }

    LaunchedEffect(message) {
        val current = message
        if (current != null) {
            snackbarHostState.showSnackbar(current)
            viewModel.clearMessage()
        }
    }

    val destinations = remember {
        listOf(
            Destination("dashboard", "首页", Icons.Rounded.Home),
            Destination("nodes", "节点", Icons.Rounded.Dns),
            Destination("logs", "日志", Icons.Rounded.Article),
            Destination("settings", "设置", Icons.Rounded.Settings)
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "dashboard"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(destination.icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    state = vpnState,
                    settings = settings,
                    selectedNode = selectedNode,
                    onToggleConnection = { viewModel.toggleConnection(context) },
                    onRoutingModeChange = viewModel::setRoutingMode,
                    onOpenNodes = {
                        navController.navigate("nodes") {
                            launchSingleTop = true
                        }
                    },
                    onOpenLogs = {
                        navController.navigate("logs") {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("nodes") {
                NodesScreen(
                    nodes = nodes,
                    selectedId = selectedNode?.id.orEmpty(),
                    testing = testing,
                    statusMessage = null,
                    onSelect = { node -> viewModel.selectNode(context, node) },
                    onDelete = viewModel::deleteNode,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onTestAll = viewModel::testAllLatency,
                    onImportClick = {
                        viewModel.clearImportMessage()
                        showImportSheet = true
                    },
                    onClearAll = viewModel::clearNodes,
                    onBatchDelete = viewModel::deleteNodes,
                    onBatchFavorite = { ids, favorite -> viewModel.setFavorite(ids, favorite) },
                    geoScanning = geoScanning,
                    canScanGeoIp = vpnState.isConnected &&
                        settings.countryRoutingEnabled &&
                        nodes.size > 1,
                    onScanGeoIp = viewModel::scanGeoIp
                )
            }
            composable("logs") {
                LogsScreen(
                    state = vpnState,
                    configJson = configPreview,
                    onClearLogs = viewModel::clearLogs,
                    onCopyConfig = {
                        if (configPreview.isNotBlank()) {
                            clipboard.setText(AnnotatedString(configPreview))
                            scope.launch {
                                snackbarHostState.showSnackbar("sing-box JSON 已复制到剪贴板")
                            }
                        }
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    settings = settings,
                    countries = countries,
                    appRuleCount = appRouteRules.size,
                    subscriptionCount = subscriptions.size,
                    onOpenAppRouting = {
                        navController.navigate("app-routing") {
                            launchSingleTop = true
                        }
                    },
                    onOpenSubscriptions = {
                        navController.navigate("subscriptions") {
                            launchSingleTop = true
                        }
                    },
                    onOpenDiagnostics = {
                        navController.navigate("diagnostics") {
                            launchSingleTop = true
                        }
                    },
                    onOpenExportBackup = {
                        navController.navigate("export-backup") {
                            launchSingleTop = true
                        }
                    },
                    onUpdate = { updated -> viewModel.updateSettings { updated } }
                )
            }
            composable("app-routing") {
                AppRoutingScreen(
                    rules = appRouteRules,
                    countries = countries,
                    multiNodeEnabled = settings.countryRoutingEnabled,
                    onBack = { navController.popBackStack() },
                    onSave = { rule -> viewModel.saveAppRouteRule(context, rule) },
                    onDelete = { packageName -> viewModel.deleteAppRouteRule(context, packageName) }
                )
            }
            composable("subscriptions") {
                SubscriptionsScreen(
                    subscriptions = subscriptions,
                    onBack = { navController.popBackStack() },
                    onAdd = { name, url -> viewModel.addSubscription(name, url) },
                    onRefresh = { id -> viewModel.refreshSubscription(id) },
                    onRefreshAll = { viewModel.refreshAllSubscriptions() },
                    onDelete = { id -> viewModel.deleteSubscription(id) },
                    onToggleAutoUpdate = { id, enabled ->
                        viewModel.setSubscriptionAutoUpdate(id, enabled)
                    }
                )
            }
            composable("diagnostics") {
                DiagnosticsScreen(
                    vpnState = vpnState,
                    selectedNode = selectedNode,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("export-backup") {
                ExportBackupScreen(
                    nodes = nodes,
                    selectedNode = selectedNode,
                    webdavUrl = settings.webdavUrl,
                    webdavUsername = settings.webdavUsername,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showImportSheet) {
        ImportBottomSheet(
            onDismiss = { showImportSheet = false },
            onImportText = { text -> viewModel.importText(text, "manual") },
            onGenerateSamples = { viewModel.generateSamples() },
            message = importMessage,
            isImporting = importing
        )
    }
}

/**
 * 底部导航目标。
 */
private data class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
)
