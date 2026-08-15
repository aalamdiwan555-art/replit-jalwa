package com.replit.jalwa

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import android.os.Build
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.replit.jalwa.data.AccountStatus
import com.replit.jalwa.data.SubscriptionManager
import com.replit.jalwa.data.SubscriptionType
import com.replit.jalwa.data.TemplateEntity
import com.replit.jalwa.data.UserEntity
import com.replit.jalwa.detection.DetectionState
import com.replit.jalwa.detection.TestAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val detectorViewModel: JalwaViewModel by viewModels()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            launchProjectionConsent()
        }
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                detectorViewModel.startDetection(result.resultCode, data)
            } else {
                detectorViewModel.showMessage("Screen-capture permission was not granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JalwaTheme {
                JalwaApp(
                    vm = detectorViewModel,
                    onRequestProjection = {
                        requestNotificationThenProjection()
                    },
                )
            }
        }
    }

    private fun requestNotificationThenProjection() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
fun JalwaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF9B8CFF),
            secondary = Color(0xFF70D5C5),
            background = Color(0xFF0D1020),
            surface = Color(0xFF171B30),
            surfaceVariant = Color(0xFF252A46),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JalwaApp(vm: JalwaViewModel, onRequestProjection: () -> Unit) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showPrivacyDialog by rememberSaveable { mutableStateOf(false) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(700)
        showSplash = false
    }
    if (showSplash) {
        SplashScreen()
        return
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    val canGoBack = state.screen !in setOf(AppScreen.LOGIN, AppScreen.SIGNUP)
    Scaffold(
        topBar = {
            if (canGoBack) {
                TopAppBar(
                    title = { Text(screenTitle(state.screen), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            when (state.screen) {
                                 AppScreen.DETECTION, AppScreen.PERMISSIONS, AppScreen.ACCOUNT,
                                 AppScreen.SETTINGS, AppScreen.PRIVACY, AppScreen.LICENSES,
                                 AppScreen.SUBSCRIPTION -> vm.show(AppScreen.HOME)
                                 AppScreen.USERS, AppScreen.TEMPLATES, AppScreen.HISTORY -> vm.show(AppScreen.ADMIN_DASHBOARD)
                                AppScreen.ADMIN_DASHBOARD -> vm.logout()
                                else -> vm.show(AppScreen.LOGIN)
                            }
                        }) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.screen) {
                AppScreen.LOGIN -> LoginScreen(vm)
                AppScreen.SIGNUP -> SignupScreen(vm)
                AppScreen.HOME -> HomeScreen(vm, state.user) {
                    vm.prepareDetection { showPrivacyDialog = true }
                }
                AppScreen.PERMISSIONS -> PermissionScreen(vm, state)
                AppScreen.DETECTION -> DetectionScreen(vm, state)
                AppScreen.ACCOUNT -> AccountScreen(vm, state)
                AppScreen.SUBSCRIPTION -> SubscriptionScreen(state)
                AppScreen.SETTINGS -> SettingsScreen(vm, state)
                AppScreen.PRIVACY -> PrivacyScreen()
                AppScreen.LICENSES -> LicensesScreen()
                AppScreen.ADMIN_LOGIN -> AdminLoginScreen(vm)
                AppScreen.ADMIN_SETUP -> AdminSetupScreen(vm)
                AppScreen.ADMIN_DASHBOARD -> AdminDashboard(vm, state)
                AppScreen.USERS -> UsersScreen(vm, state)
                AppScreen.TEMPLATES -> TemplatesScreen(vm, state.templates)
                 AppScreen.HISTORY -> HistoryScreen(state)
            }
        }
    }
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Before you start") },
            text = {
                Text(
                    "Screen capture is used only while you explicitly start the detector. " +
                        "Frames are processed locally and are not uploaded.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPrivacyDialog = false
                    onRequestProjection()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(68.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(18.dp))
            Text("ATPILOT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Private template detection", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun screenTitle(screen: AppScreen): String = when (screen) {
    AppScreen.HOME -> "Control center"
    AppScreen.PERMISSIONS -> "Permission center"
    AppScreen.DETECTION -> "Active test"
    AppScreen.ACCOUNT -> "Account"
    AppScreen.SUBSCRIPTION -> "Subscription"
    AppScreen.SETTINGS -> "Settings"
    AppScreen.PRIVACY -> "Privacy"
    AppScreen.LICENSES -> "Open-source licenses"
    AppScreen.ADMIN_LOGIN, AppScreen.ADMIN_SETUP -> "Administrator"
    AppScreen.ADMIN_DASHBOARD -> "Admin dashboard"
    AppScreen.USERS -> "User management"
    AppScreen.TEMPLATES -> "Template management"
    AppScreen.HISTORY -> "Click history"
    else -> "ATPILOT"
}

@Composable
private fun LoginScreen(vm: JalwaViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AuthShell(
        title = "Welcome back",
        subtitle = "A private testing utility that stays on your device.",
    ) {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        PasswordField(password, { password = it }, "Password")
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.login(email, password) }, Modifier.fillMaxWidth()) { Text("Sign in") }
        TextButton(onClick = { vm.show(AppScreen.SIGNUP) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Create an account") }
    }
}

@Composable
private fun SignupScreen(vm: JalwaViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AuthShell("Create your account", "Your account is kept local and requires administrator approval.") {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        PasswordField(password, { password = it }, "Password (8+ characters)")
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.signup(name, email, password) }, Modifier.fillMaxWidth()) { Text("Create account") }
        TextButton(onClick = { vm.show(AppScreen.LOGIN) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Back to sign in") }
    }
}

@Composable
private fun AuthShell(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            Modifier.size(58.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Security, "ATPILOT", tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.height(34.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        content()
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onValueChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

@Composable
private fun HomeScreen(vm: JalwaViewModel, user: UserEntity?, onStart: () -> Unit) {
    var nameTapCount by remember { mutableIntStateOf(0) }
    var firstNameTapAt by remember { mutableLongStateOf(0L) }

    fun handleNameTap() {
        val now = SystemClock.elapsedRealtime()
        if (firstNameTapAt != 0L && now - firstNameTapAt > 2_500L) {
            nameTapCount = 0
            firstNameTapAt = 0L
        }

        if (nameTapCount == 0) firstNameTapAt = now
        nameTapCount += 1
        if (nameTapCount == 10) {
            nameTapCount = 0
            firstNameTapAt = 0L
            vm.show(AppScreen.ADMIN_LOGIN)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text(
            "Account details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(18.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                AccountField(
                    "Name",
                    user?.name ?: "—",
                    Modifier.clickable(enabled = user != null, onClick = ::handleNameTap),
                )
                AccountField("Email", user?.email ?: "—")
                AccountField(
                    "Status",
                    user?.accountStatus?.name
                        ?.lowercase()
                        ?.replaceFirstChar { it.uppercase() }
                        ?: "—",
                )
                AccountField(
                    "Subscription",
                    user?.let { SubscriptionManager.expiryLabel(it) } ?: "—",
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = onStart,
            enabled = user != null && SubscriptionManager.isActive(user),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Start")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.show(AppScreen.SUBSCRIPTION) }, Modifier.fillMaxWidth()) {
            Text("Subscription details")
        }
        OutlinedButton(onClick = { vm.show(AppScreen.SETTINGS) }, Modifier.fillMaxWidth()) {
            Text("Detection settings")
        }
        OutlinedButton(onClick = { vm.show(AppScreen.PRIVACY) }, Modifier.fillMaxWidth()) {
            Text("Privacy")
        }
        OutlinedButton(onClick = { vm.show(AppScreen.LICENSES) }, Modifier.fillMaxWidth()) {
            Text("Open-source licenses")
        }
    }
}

@Composable
private fun PermissionScreen(vm: JalwaViewModel, state: UiState) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.updatePermissionStatus() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Everything is explicit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("ATPILOT never silently enables access. Grant only the permissions you understand.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(22.dp))
        PermissionRow("Accessibility service", state.permissionStatus.accessibility) { PermissionCenter.openAccessibility(context) }
        PermissionRow("Overlay access", state.permissionStatus.overlay) { PermissionCenter.openOverlay(context) }
        PermissionRow("Private template storage", state.permissionStatus.privateStorage) {}
        PermissionRow("Notifications", state.permissionStatus.notifications) { PermissionCenter.openNotifications(context) }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = { vm.updatePermissionStatus() }, Modifier.fillMaxWidth()) { Text("Refresh permission status") }
        Spacer(Modifier.height(24.dp))
        Text("Why these permissions?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Accessibility is used only for the one configured test action. Overlay access supports a visible status surface. Notifications keep the active capture visible. Screen-capture consent is requested separately for each start, and frames are processed in memory.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, openSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (granted) Icons.Default.CheckCircle else Icons.Default.Close, null, tint = if (granted) Color(0xFF70D5C5) else Color(0xFFFF7C8A))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(if (granted) "Granted" else "Missing", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!granted) TextButton(onClick = openSettings) { Text("Open settings") }
        }
    }
}

@Composable
private fun DetectionScreen(vm: JalwaViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .18f))) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color(0xFF70D5C5), CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(state.detectionStatus.state.name, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Text(state.detectionStatus.message)
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Text("Confidence ${"%.0f".format(state.detectionStatus.confidence * 100)}%")
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Configured action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(actionLabel(state.action), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
             OutlinedButton(
                 onClick = {
                     if (state.detectionStatus.state == DetectionState.PAUSED) {
                         vm.resumeDetection()
                     } else {
                         vm.pauseDetection()
                     }
                 },
                 Modifier.weight(1f),
             ) {
                 Text(if (state.detectionStatus.state == DetectionState.PAUSED) "Resume" else "Pause")
             }
            Button(onClick = { vm.stopDetection() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7080))) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Stop now")
            }
        }
    }
}

@Composable
private fun AccountScreen(vm: JalwaViewModel, state: UiState) {
    val user = state.user
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Your local account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        AccountField("Name", user?.name ?: "—")
        AccountField("Email", user?.email ?: "—")
        AccountField("Status", user?.accountStatus?.name ?: "—")
        AccountField("Subscription", user?.let { SubscriptionManager.expiryLabel(it) } ?: "—")
        Spacer(Modifier.height(18.dp))
        Text("Local-only note", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Because this build has no backend, administrator changes made on one device cannot propagate to another device automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { vm.logout() }, Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun SubscriptionScreen(state: UiState) {
    val user = state.user
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Local subscription", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        AccountField("Account status", user?.accountStatus?.name ?: "—")
        AccountField("Plan", user?.subscriptionType?.name ?: "NONE")
        AccountField("Access", user?.let { if (SubscriptionManager.isActive(it)) "Active" else "Inactive" } ?: "—")
        AccountField("Expiry", user?.let { SubscriptionManager.expiryLabel(it) } ?: "—")
        Text(
            "Subscriptions are assigned locally by this device's administrator. No payment processor or cloud service is connected.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SettingsScreen(vm: JalwaViewModel, state: UiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Detection settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Choose one optional, user-requested test action. The safe default is no action.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )
        TestAction.values().forEach { action ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setAction(action) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = state.action == action,
                    onClick = { vm.setAction(action) },
                )
                Spacer(Modifier.width(10.dp))
                Text(actionLabel(action))
            }
        }
        Text(
            "Frame interval, confidence, cooldown, and the optional safe region are configured by the administrator and remain on this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

@Composable
private fun PrivacyScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Privacy by default", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "Screen capture starts only after you press Start, confirm the privacy notice, and accept Android's system screen-capture dialog.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Frames are processed in memory, dropped when the processor is busy, and released immediately. The app does not upload, archive, or place captures in Gallery, DCIM, Pictures, Downloads, or MediaStore.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "The foreground notification stays visible while capture is active, and Stop is always available from the app, overlay, and notification.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicensesScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Open-source licenses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Smart AutoClicker / Klick'r", fontWeight = FontWeight.SemiBold)
        Text(
            "Reference: https://github.com/Nain57/Smart-AutoClicker\nLicense: GNU GPL-3.0\nReused files in this build: none",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "This app's new source is MIT-licensed. AndroidX, Kotlin, Room, and Jetpack Compose retain their upstream licenses.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AdminLoginScreen(vm: JalwaViewModel) {
    var email by rememberSaveable { mutableStateOf("diwanatik84@gmail.com") }
    var password by rememberSaveable { mutableStateOf("") }
    AuthShell("Administrator access", "This protected area is initialized once per device.") {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Administrator email") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        PasswordField(password, { password = it }, "Administrator password")
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.adminLogin(email, password) }, Modifier.fillMaxWidth()) { Text("Continue") }
        TextButton(onClick = { vm.show(AppScreen.LOGIN) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Exit") }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { vm.show(AppScreen.ADMIN_SETUP) }, Modifier.align(Alignment.CenterHorizontally)) { Text("First-run setup") }
    }
}

@Composable
private fun AdminSetupScreen(vm: JalwaViewModel) {
    var email by rememberSaveable { mutableStateOf("diwanatik84@gmail.com") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    AuthShell("Initialize administrator", "The password is hashed locally and is never stored in source code.") {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Administrator email") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        PasswordField(password, { password = it }, "New password (12+ characters)")
        Spacer(Modifier.height(12.dp))
        PasswordField(confirmation, { confirmation = it }, "Confirm password")
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            if (password != confirmation) vm.clearMessage().also { vm.show(AppScreen.ADMIN_SETUP) }
            else vm.setupAdmin(email, password)
        }, Modifier.fillMaxWidth()) { Text("Initialize securely") }
        Text("Use a unique password. This administrator controls only this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun AdminDashboard(vm: JalwaViewModel, state: UiState) {
    val total = state.users.size
    val pending = state.users.count { it.accountStatus == AccountStatus.PENDING }
    val approved = state.users.count { it.accountStatus == AccountStatus.APPROVED }
    val rejected = state.users.count { it.accountStatus == AccountStatus.REJECTED }
    val expired = state.users.count { it.accountStatus == AccountStatus.EXPIRED || (it.accountStatus == AccountStatus.APPROVED && !SubscriptionManager.isActive(it)) }
    val active = state.users.count { SubscriptionManager.isActive(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        Text("Device overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Users", total, Modifier.weight(1f))
            StatCard("Active", active, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Pending", pending, Modifier.weight(1f))
            StatCard("Expired", expired, Modifier.weight(1f))
        }
        Spacer(Modifier.height(22.dp))
        AdminNavButton("User management", "$approved approved • $rejected rejected") { vm.show(AppScreen.USERS) }
        AdminNavButton("Template management", "${state.templates.size} private templates") { vm.show(AppScreen.TEMPLATES) }
        AdminNavButton("Click history", "${state.clickHistory.size} recent target actions") { vm.show(AppScreen.HISTORY) }
        Spacer(Modifier.height(22.dp))
        Text("Security posture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Passwords are salted and hashed. Templates stay under app-private storage. No credentials, screenshots, or template contents are logged.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdminNavButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(Icons.Default.Menu, null)
        }
    }
}

@Composable
private fun UsersScreen(vm: JalwaViewModel, state: UiState) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = state.users.filter {
        query.isBlank() || it.name.contains(query, true) || it.email.contains(query, true)
    }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search users") }, singleLine = true)
        Spacer(Modifier.height(14.dp))
        if (filtered.isEmpty()) {
            Text("No users found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered, key = { it.id }) { user ->
                    UserRow(vm, user, state.clickHistory.filter { event -> event.userId == user.id })
                }
            }
        }
    }
}

@Composable
private fun UserRow(vm: JalwaViewModel, user: UserEntity, events: List<com.replit.jalwa.data.ClickHistoryEntity>) {
    var showSubscriptions by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var customDays by rememberSaveable(user.id) { mutableStateOf("7") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(user.name, fontWeight = FontWeight.SemiBold)
                    Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(user.accountStatus.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("Subscription: ${SubscriptionManager.expiryLabel(user)}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Target actions recorded: ${events.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (user.accountStatus) {
                    AccountStatus.PENDING -> {
                        Button(onClick = { vm.approve(user) }, Modifier.weight(1f)) { Text("Approve") }
                        OutlinedButton(onClick = { vm.reject(user) }, Modifier.weight(1f)) { Text("Reject") }
                    }
                    AccountStatus.DISABLED -> Button(onClick = { vm.reEnable(user) }, Modifier.weight(1f)) { Text("Re-enable") }
                    else -> OutlinedButton(onClick = { vm.disable(user) }, Modifier.weight(1f)) { Text("Disable") }
                }
                OutlinedButton(onClick = { showSubscriptions = !showSubscriptions }, Modifier.weight(1f)) { Text("Subscription") }
            }
            OutlinedButton(
                onClick = { showHistory = !showHistory },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(if (showHistory) "Hide target activity" else "View target activity")
            }
            if (showSubscriptions) {
                Text("Assign duration", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubscriptionChoice("1 day", { vm.assignSubscription(user, SubscriptionType.ONE_DAY) })
                    SubscriptionChoice("2 days", { vm.assignSubscription(user, SubscriptionType.TWO_DAYS) })
                    SubscriptionChoice("3 days", { vm.assignSubscription(user, SubscriptionType.THREE_DAYS) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubscriptionChoice("Lifetime", { vm.assignSubscription(user, SubscriptionType.LIFETIME) })
                    OutlinedTextField(
                        customDays,
                        { customDays = it.filter(Char::isDigit).take(3) },
                        Modifier.weight(1f),
                        label = { Text("Days") },
                        singleLine = true,
                    )
                    SubscriptionChoice("Apply", {
                        customDays.toIntOrNull()?.takeIf { it > 0 }?.let {
                            vm.assignSubscription(user, SubscriptionType.CUSTOM, it)
                        }
                    })
                }
            }
            if (showHistory) {
                if (events.isEmpty()) {
                    Text(
                        "No target actions recorded for this user.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    events.take(10).forEach { event -> HistoryRow(event, user.name) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.SubscriptionChoice(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun TemplatesScreen(vm: JalwaViewModel, templates: List<TemplateEntity>) {
    var name by rememberSaveable { mutableStateOf("") }
    var threshold by rememberSaveable { mutableStateOf("0.90") }
    var region by rememberSaveable { mutableStateOf("") }
    var pendingName by remember { mutableStateOf("") }
    var pendingThreshold by remember { mutableStateOf(0.90f) }
    var pendingRegion by remember { mutableStateOf("") }
    var replacementId by remember { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val replacing = replacementId?.let { id -> templates.firstOrNull { it.id == id } }
        if (replacing != null) {
            vm.replaceTemplate(replacing, uri)
            replacementId = null
        } else {
            vm.importTemplate(uri, pendingName, pendingThreshold, pendingRegion)
            name = ""
        }
    }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Private templates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Imported files are copied into app-private storage. Users never receive a preview.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Template name") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(threshold, { threshold = it }, Modifier.fillMaxWidth(), label = { Text("Matching threshold (0.00–1.00)") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            region,
            { region = it },
            Modifier.fillMaxWidth(),
            label = { Text("Safe region (left,top,right,bottom), optional") },
            supportingText = { Text("Coordinates use the captured screen size.") },
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                pendingName = name.trim()
                pendingThreshold = threshold.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.90f
                pendingRegion = region.trim()
                if (pendingName.isNotBlank()) picker.launch(arrayOf("image/*"))
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import privately") }
        Spacer(Modifier.height(20.dp))
        if (templates.isEmpty()) {
            Text("No templates imported yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(templates, key = { it.id }) { template ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(template.name, fontWeight = FontWeight.SemiBold)
                                Text("Threshold ${"%.2f".format(template.threshold)} • ${if (template.enabled) "Enabled" else "Disabled"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.Switch(
                                checked = template.enabled,
                                onCheckedChange = { vm.updateTemplate(template.copy(enabled = it)) },
                            )
                            TextButton(onClick = {
                                replacementId = template.id
                                picker.launch(arrayOf("image/*"))
                            }) { Text("Replace") }
                            IconButton(onClick = { vm.deleteTemplate(template) }) { Icon(Icons.Default.Delete, "Delete template") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: UiState) {
    val usersById = state.users.associateBy { it.id }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Recent target activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Only target matches observed while a user-started test was running are recorded. Screen contents are never stored.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (state.clickHistory.isEmpty()) {
            Text("No target actions recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.clickHistory, key = { it.id }) { event ->
                    HistoryRow(event, usersById[event.userId]?.name ?: "Unknown user")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(event: com.replit.jalwa.data.ClickHistoryEntity, userName: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(userName, fontWeight = FontWeight.SemiBold)
            Text(
                "${event.templateName} • ${actionLabel(event.action)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.occurredAt))} • Confidence ${"%.0f".format(event.confidence * 100)}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun actionLabel(action: TestAction): String = when (action) {
    TestAction.NONE -> "No action (safe default)"
    TestAction.BACK -> "System Back"
    TestAction.HOME -> "System Home"
    TestAction.NOTIFICATIONS -> "Open notifications"
}

private fun actionLabel(action: String): String = when (action) {
    TestAction.BACK.name -> "System Back"
    TestAction.HOME.name -> "System Home"
    TestAction.NOTIFICATIONS.name -> "Open notifications"
    TestAction.NONE.name -> "No configured action"
    else -> action
}