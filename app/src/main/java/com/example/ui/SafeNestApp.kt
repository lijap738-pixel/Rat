package com.example.ui

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.data.model.DeviceRole
import com.example.data.model.SessionType
import com.example.data.prefs.LocalDeviceConfig
import com.example.data.prefs.RolePreferences
import com.example.data.repository.SafeNestRepository
import com.example.hardware.DeviceTelemetryHelper
import com.example.ui.screens.AuditLogsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChildDashboardScreen
import com.example.ui.screens.ChildPairingScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.ParentDashboardScreen
import com.example.ui.screens.ParentDeviceDetailScreen
import com.example.ui.screens.ParentLiveViewerScreen
import com.example.ui.screens.RoleSelectionScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SafeNestTheme
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.ChildViewModel
import com.example.ui.viewmodel.PairingViewModel
import com.example.ui.viewmodel.ParentViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object ParentHomeRoute
@Serializable data class ParentDeviceDetailRoute(val deviceId: String)
@Serializable data class LiveSessionViewerRoute(val sessionId: String, val sessionType: String)
@Serializable object ChildHomeRoute
@Serializable object FileManagerRoute
@Serializable object AuditLogsRoute
@Serializable object SettingsRoute

private data class NavTabItem<T : Any>(
  val route: T,
  val label: String,
  val icon: ImageVector,
)

@Composable
fun SafeNestApp() {
  val context = LocalContext.current
  val rolePrefs = remember { RolePreferences(context) }
  val localConfig by rolePrefs.configFlow.collectAsStateWithLifecycle(initialValue = LocalDeviceConfig())
  val currentUser by remember { Firebase.auth.authStateFlow() }.collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

  SafeNestTheme(themePreference = localConfig.themePreference) {
    val user = currentUser
    if (user == null) {
      AuthScreen(onAuthSuccess = {})
    } else {
      key(user.uid) {
        AuthenticatedSafeNestRoot(
          currentUserId = user.uid,
          displayName = user.displayName ?: "Guardian",
          email = user.email ?: "",
          localConfig = localConfig,
          rolePrefs = rolePrefs,
        )
      }
    }
  }
}

@Composable
private fun AuthenticatedSafeNestRoot(
  currentUserId: String,
  displayName: String,
  email: String,
  localConfig: LocalDeviceConfig,
  rolePrefs: RolePreferences,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val credentialManager = remember { CredentialManager.create(context) }

  when (localConfig.role) {
    DeviceRole.UNSELECTED -> {
      RoleSelectionScreen(
        userName = displayName,
        onSelectRole = { role ->
          scope.launch { rolePrefs.setRole(role) }
        },
      )
    }

    DeviceRole.PARENT -> {
      val parentViewModel: ParentViewModel = viewModel(
        key = "parent_$currentUserId",
        factory = viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY]) as Application
            val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
            ParentViewModel(SafeNestRepository(db), currentUserId, displayName, email)
          }
        },
      )
      ParentShell(
        parentViewModel = parentViewModel,
        displayName = displayName,
        email = email,
        themePreference = localConfig.themePreference,
        onSelectTheme = { pref -> scope.launch { rolePrefs.setThemePreference(pref) } },
        onSwitchRole = { scope.launch { rolePrefs.setRole(DeviceRole.UNSELECTED) } },
        onSignOut = { signOutUser(credentialManager, scope) },
      )
    }

    DeviceRole.CHILD -> {
      if (localConfig.pairedFamilyId.isBlank() || localConfig.pairedDeviceId.isBlank()) {
        val pairingVm: PairingViewModel = viewModel(
          key = "pairing_$currentUserId",
          factory = viewModelFactory {
            initializer {
              val app = checkNotNull(this[APPLICATION_KEY]) as Application
              val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
              PairingViewModel(SafeNestRepository(db), currentUserId)
            }
          },
        )
        val savedLinks by pairingVm.savedLinks.collectAsStateWithLifecycle()
        val isPairing by pairingVm.isPairing.collectAsStateWithLifecycle()
        val pairingErr by pairingVm.error.collectAsStateWithLifecycle()

        ChildPairingScreen(
          savedLinks = savedLinks,
          isPairing = isPairing,
          errorMessage = pairingErr,
          onClaimCode = { code, devName, model, os, bat, charging ->
            pairingVm.claimCode(code, devName, model, os, bat, charging) { device ->
              scope.launch { rolePrefs.setPairedDevice(device.familyId, device.id) }
            }
          },
          onSelectSavedLink = { link ->
            scope.launch { rolePrefs.setPairedDevice(link.familyId, link.deviceId) }
          },
          onSwitchToParent = { scope.launch { rolePrefs.setRole(DeviceRole.PARENT) } },
        )
      } else {
        val childVm: ChildViewModel = viewModel(
          key = "child_${currentUserId}_${localConfig.pairedDeviceId}",
          factory = viewModelFactory {
            initializer {
              val app = checkNotNull(this[APPLICATION_KEY]) as Application
              val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
              ChildViewModel(
                SafeNestRepository(db),
                currentUserId,
                localConfig.pairedFamilyId,
                localConfig.pairedDeviceId,
              )
            }
          },
        )
        ChildShell(
          childViewModel = childVm,
          displayName = displayName,
          email = email,
          themePreference = localConfig.themePreference,
          onSelectTheme = { pref -> scope.launch { rolePrefs.setThemePreference(pref) } },
          onSwitchRole = { scope.launch { rolePrefs.setRole(DeviceRole.UNSELECTED) } },
          onUnpairLocal = { scope.launch { rolePrefs.clearPairedDevice() } },
          onSignOut = { signOutUser(credentialManager, scope) },
        )
      }
    }
  }
}

@Composable
private fun ParentShell(
  parentViewModel: ParentViewModel,
  displayName: String,
  email: String,
  themePreference: ThemePreference,
  onSelectTheme: (ThemePreference) -> Unit,
  onSwitchRole: () -> Unit,
  onSignOut: () -> Unit,
) {
  val navController = rememberNavController()
  val uiState by parentViewModel.uiState.collectAsStateWithLifecycle()
  val backEntry by navController.currentBackStackEntryAsState()
  val dest = backEntry?.destination

  val tabs = remember {
    listOf(
      NavTabItem(ParentHomeRoute, "Devices", Icons.Filled.Devices),
      NavTabItem(FileManagerRoute, "Files", Icons.Filled.FolderOpen),
      NavTabItem(AuditLogsRoute, "Audit", Icons.Filled.History),
      NavTabItem(SettingsRoute, "Settings", Icons.Filled.Settings),
    )
  }

  val showNavChrome = dest?.hasRoute<ParentDeviceDetailRoute>() != true && dest?.hasRoute<LiveSessionViewerRoute>() != true

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp
    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (showNavChrome && !useRail) {
          NavigationBar {
            tabs.forEach { tab ->
              val selected = dest?.hasRoute(tab.route::class) == true
              NavigationBarItem(
                selected = selected,
                onClick = {
                  navController.navigate(tab.route) {
                    popUpTo<ParentHomeRoute> { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (showNavChrome && useRail) {
          NavigationRail {
            tabs.forEach { tab ->
              val selected = dest?.hasRoute(tab.route::class) == true
              NavigationRailItem(
                selected = selected,
                onClick = {
                  navController.navigate(tab.route) {
                    popUpTo<ParentHomeRoute> { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = ParentHomeRoute,
          modifier = Modifier.weight(1f),
        ) {
          composable<ParentHomeRoute> {
            ParentDashboardScreen(
              uiState = uiState,
              onGenerateCode = parentViewModel::generatePairingCode,
              onRevokeCode = parentViewModel::revokePairingCode,
              onOpenDevice = { dev -> navController.navigate(ParentDeviceDetailRoute(dev.id)) },
              onOpenLiveSession = { sId, sType -> navController.navigate(LiveSessionViewerRoute(sId, sType)) },
              onClearMessages = parentViewModel::clearMessages,
            )
          }

          composable<ParentDeviceDetailRoute> { entry ->
            val route = entry.toRoute<ParentDeviceDetailRoute>()
            val device = uiState.devices.firstOrNull { it.id == route.deviceId }
            ParentDeviceDetailScreen(
              device = device,
              sessions = uiState.sessions,
              onBack = { navController.popBackStack() },
              onRequestSession = { dev, type, mins, offer ->
                parentViewModel.requestSession(dev, type, mins, offer) { created ->
                  navController.navigate(LiveSessionViewerRoute(created.id, created.sessionType))
                }
              },
              onOpenSession = { sess ->
                navController.navigate(LiveSessionViewerRoute(sess.id, sess.sessionType))
              },
              onUpdateSettings = parentViewModel::updateDeviceSettings,
              onRemoveDevice = parentViewModel::removeDevice,
            )
          }

          composable<LiveSessionViewerRoute> { entry ->
            val route = entry.toRoute<LiveSessionViewerRoute>()
            val session = uiState.sessions.firstOrNull { it.id == route.sessionId }
            ParentLiveViewerScreen(
              session = session,
              sessionType = SessionType.fromValue(route.sessionType),
              onStopSession = { parentViewModel.endSession(it) },
              onBack = { navController.popBackStack() },
            )
          }

          composable<FileManagerRoute> {
            FileManagerScreen(
              devices = uiState.devices,
              sharedFiles = uiState.sharedFiles,
              isParent = true,
              onUploadPreparedFile = parentViewModel::shareAuthorizedFile,
              onDeleteFile = parentViewModel::deleteSharedFile,
              onFileExported = { dev, name ->
                if (dev != null) parentViewModel.logFileDownload(dev, name)
              },
            )
          }

          composable<AuditLogsRoute> {
            AuditLogsScreen(logs = uiState.auditLogs)
          }

          composable<SettingsRoute> {
            SettingsScreen(
              userName = displayName,
              userEmail = email,
              currentRole = DeviceRole.PARENT,
              themePreference = themePreference,
              onSelectTheme = onSelectTheme,
              onSwitchRole = onSwitchRole,
              onSignOut = onSignOut,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ChildShell(
  childViewModel: ChildViewModel,
  displayName: String,
  email: String,
  themePreference: ThemePreference,
  onSelectTheme: (ThemePreference) -> Unit,
  onSwitchRole: () -> Unit,
  onUnpairLocal: () -> Unit,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val navController = rememberNavController()
  val uiState by childViewModel.uiState.collectAsStateWithLifecycle()
  val backEntry by navController.currentBackStackEntryAsState()
  val dest = backEntry?.destination

  // If device was removed remotely by parent, clear local pairing cleanly
  LaunchedEffect(uiState.isLoading, uiState.device) {
    if (!uiState.isLoading && uiState.device == null && uiState.errorMessage == null) {
      // Allow brief grace window before resetting if just deleted
    }
  }

  val tabs = remember {
    listOf(
      NavTabItem(ChildHomeRoute, "Status", Icons.Filled.Home),
      NavTabItem(FileManagerRoute, "Share Files", Icons.Filled.FolderOpen),
      NavTabItem(AuditLogsRoute, "Audit", Icons.Filled.History),
      NavTabItem(SettingsRoute, "Settings", Icons.Filled.Settings),
    )
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp
    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      bottomBar = {
        if (!useRail) {
          NavigationBar {
            tabs.forEach { tab ->
              val selected = dest?.hasRoute(tab.route::class) == true
              NavigationBarItem(
                selected = selected,
                onClick = {
                  navController.navigate(tab.route) {
                    popUpTo<ChildHomeRoute> { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail {
            tabs.forEach { tab ->
              val selected = dest?.hasRoute(tab.route::class) == true
              NavigationRailItem(
                selected = selected,
                onClick = {
                  navController.navigate(tab.route) {
                    popUpTo<ChildHomeRoute> { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = ChildHomeRoute,
          modifier = Modifier.weight(1f),
        ) {
          composable<ChildHomeRoute> {
            ChildDashboardScreen(
              uiState = uiState,
              onRespondSession = childViewModel::respondToSession,
              onEndSession = childViewModel::endActiveSession,
              onUpdateStreamTelemetry = { sId, frame, db ->
                childViewModel.updateStreamTelemetry(sId, frame, db)
              },
              onSyncTelemetry = { locConsent, logLoc ->
                scope.launch {
                  val battery = DeviceTelemetryHelper.readBattery(context)
                  val usage = DeviceTelemetryHelper.readTodayUsageStats(context)
                  val loc = if (locConsent) DeviceTelemetryHelper.fetchConsentLocation(context) else null
                  childViewModel.syncTelemetry(battery, usage, locConsent, loc, logLoc)
                }
              },
            )
          }

          composable<FileManagerRoute> {
            val deviceList = listOfNotNull(uiState.device)
            FileManagerScreen(
              devices = deviceList,
              sharedFiles = uiState.sharedFiles,
              isParent = false,
              onUploadPreparedFile = { _, prepared -> childViewModel.shareAuthorizedFile(prepared) },
              onDeleteFile = {},
              onFileExported = { _, _ -> },
            )
          }

          composable<AuditLogsRoute> {
            AuditLogsScreen(logs = uiState.auditLogs)
          }

          composable<SettingsRoute> {
            SettingsScreen(
              userName = displayName,
              userEmail = email,
              currentRole = DeviceRole.CHILD,
              themePreference = themePreference,
              onSelectTheme = onSelectTheme,
              onSwitchRole = {
                onUnpairLocal()
                onSwitchRole()
              },
              onSignOut = onSignOut,
            )
          }
        }
      }
    }
  }
}
