package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.AppleGlassBackground
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.EventsScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.PairingScreen
import com.example.ui.theme.LocalAppleColors
import kotlinx.serialization.Serializable

@Serializable
sealed interface BridgeRoute {
    @Serializable data object Overview : BridgeRoute
    @Serializable data object Events : BridgeRoute
    @Serializable data object Pairing : BridgeRoute
    @Serializable data object Diagnostics : BridgeRoute
}

private data class NavTab(
    val route: BridgeRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

private val NAV_TABS = listOf(
    NavTab(BridgeRoute.Overview, "Bridge", Icons.Rounded.Home, Icons.Outlined.Home, "nav_bridge"),
    NavTab(BridgeRoute.Events, "Events", Icons.Rounded.Inbox, Icons.Outlined.Inbox, "nav_events"),
    NavTab(BridgeRoute.Pairing, "Pairing", Icons.Rounded.Key, Icons.Outlined.Key, "nav_pairing"),
    NavTab(BridgeRoute.Diagnostics, "System", Icons.Rounded.MonitorHeart, Icons.Outlined.MonitorHeart, "nav_system")
)

@Composable
fun PhoneBridgeApp(
    viewModel: PhoneBridgeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusBanner) {
        val msg = state.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val navigateTo: (BridgeRoute) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    AppleGlassBackground {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.statusBars,
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(bottom = if (useRail) 12.dp else 92.dp)
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useRail) {
                            NavigationRail(
                                modifier = Modifier.fillMaxHeight(),
                                containerColor = LocalAppleColors.current.glassFill
                            ) {
                                NAV_TABS.forEach { tab ->
                                    val selected = currentDest?.hasRoute(tab.route::class) == true
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { navigateTo(tab.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                contentDescription = tab.label
                                            )
                                        },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag(tab.tag)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = BridgeRoute.Overview,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 680.dp)
                            ) {
                                composable<BridgeRoute.Overview> {
                                    OverviewScreen(
                                        state = state,
                                        onSelectRole = viewModel::setRole,
                                        onCaptureEvent = { type, title, body ->
                                            viewModel.captureAndQueueEvent(type, title, body)
                                        },
                                        onFlushOutbox = viewModel::flushOutboxNow,
                                        onSyncInbox = viewModel::syncInboxNow,
                                        onToggleRealtimeWs = viewModel::setRealtimeWebSocket,
                                        onToggleAutoCalendar = viewModel::setAutoAddCalendar,
                                        onNavigateToPairing = { navigateTo(BridgeRoute.Pairing) }
                                    )
                                }
                                composable<BridgeRoute.Events> {
                                    EventsScreen(
                                        state = state,
                                        onMarkRead = viewModel::markEventRead,
                                        onAddToCalendar = viewModel::addEventToCalendar,
                                        onFlushOutbox = viewModel::flushOutboxNow,
                                        onSyncInbox = viewModel::syncInboxNow
                                    )
                                }
                                composable<BridgeRoute.Pairing> {
                                    PairingScreen(
                                        state = state,
                                        onUpdateRelayUrl = viewModel::updateRelayUrl,
                                        onUpdateDeviceName = viewModel::updateDeviceName,
                                        onInitiatePrimaryPairing = viewModel::initiatePrimaryPairing,
                                        onPollPrimaryPairing = viewModel::pollPrimaryPairingCompletion,
                                        onCompleteSecondaryPairing = viewModel::completeSecondaryPairing,
                                        onRotateKeys = viewModel::rotateKeys,
                                        onRevokePairing = viewModel::revokePairing
                                    )
                                }
                                composable<BridgeRoute.Diagnostics> {
                                    DiagnosticsScreen(
                                        state = state,
                                        onRefreshDiagnostics = viewModel::refreshDiagnostics
                                    )
                                }
                            }
                        }
                    }

                    if (!useRail) {
                        FloatingGlassNavBar(
                            currentDest = currentDest,
                            onSelectTab = navigateTo,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingGlassNavBar(
    currentDest: androidx.navigation.NavDestination?,
    onSelectTab: (BridgeRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val apple = LocalAppleColors.current
    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 420.dp),
        shape = RoundedCornerShape(32.dp),
        color = apple.glassFillElevated,
        tonalElevation = 0.dp,
        shadowElevation = if (apple.isDark) 0.dp else 14.dp,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(apple.glassHighlight, apple.glassEdge)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_TABS.forEach { tab ->
                val selected = currentDest?.hasRoute(tab.route::class) == true
                val pillColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                    animationSpec = spring(),
                    label = "nav_pill"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else apple.subtleText,
                    animationSpec = spring(),
                    label = "nav_fg"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 50.dp)
                        .clip(CircleShape)
                        .background(pillColor)
                        .clickable { onSelectTab(tab.route) }
                        .testTag(tab.tag)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                        tint = contentColor,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
