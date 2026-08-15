package com.replit.jalwa

import android.app.Application
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.replit.jalwa.accessibility.TestAutomationService
import com.replit.jalwa.data.AccountStatus
import com.replit.jalwa.data.AdminEntity
import com.replit.jalwa.data.AppDatabase
import com.replit.jalwa.data.ClickHistoryEntity
import com.replit.jalwa.data.DeviceSyncClient
import com.replit.jalwa.data.PasswordHasher
import com.replit.jalwa.data.RateLimiter
import com.replit.jalwa.data.SessionManager
import com.replit.jalwa.data.SubscriptionManager
import com.replit.jalwa.data.SubscriptionType
import com.replit.jalwa.data.TemplateBootstrapper
import com.replit.jalwa.data.TemplateEntity
import com.replit.jalwa.data.TemplateStore
import com.replit.jalwa.data.UserEntity
import com.replit.jalwa.detection.DetectionState
import com.replit.jalwa.detection.DetectionStatus
import com.replit.jalwa.detection.TestAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class AppScreen { LOGIN, SIGNUP, HOME, PERMISSIONS, DETECTION, ACCOUNT, ADMIN_LOGIN, ADMIN_SETUP, ADMIN_DASHBOARD, USERS, TEMPLATES, HISTORY }

data class UiState(
    val screen: AppScreen = AppScreen.LOGIN,
    val user: UserEntity? = null,
    val users: List<UserEntity> = emptyList(),
    val templates: List<TemplateEntity> = emptyList(),
    val clickHistory: List<ClickHistoryEntity> = emptyList(),
    val adminConfigured: Boolean = false,
    val message: String? = null,
    val permissionStatus: PermissionStatus = PermissionStatus(false, false),
    val detectionStatus: DetectionStatus = DetectionStatus(),
    val action: TestAction = TestAction.NONE,
    val threshold: Float = 0.90f,
)

class JalwaViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.get(application)
    private val users = database.userDao()
    private val admins = database.adminDao()
    private val templates = database.templateDao()
    private val clickHistory = database.clickHistoryDao()
    private val session = SessionManager()
    private val templateStore = TemplateStore(application)
    private val loginLimiter = RateLimiter()
    private val deviceSync = DeviceSyncClient(application)
    private var deviceSyncJob: kotlinx.coroutines.Job? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            admins.find()?.let { _state.value = _state.value.copy(adminConfigured = true) }
        }
        viewModelScope.launch {
            TemplateBootstrapper(getApplication(), templateStore, templates).seedIfEmpty()
        }
        viewModelScope.launch {
            users.observeAll().collect { list -> _state.value = _state.value.copy(users = list) }
        }
        viewModelScope.launch {
            templates.observeAll().collect { list -> _state.value = _state.value.copy(templates = list) }
        }
        viewModelScope.launch {
            clickHistory.observeRecent().collect { list -> _state.value = _state.value.copy(clickHistory = list) }
        }
    }

    fun show(screen: AppScreen) {
        if (screen.isAdminProtected() && !session.isAdminSessionActive()) {
            _state.value = _state.value.copy(
                screen = AppScreen.ADMIN_LOGIN,
                message = "Administrator sign-in is required",
            )
            return
        }
        _state.value = _state.value.copy(screen = screen, message = null)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun signup(name: String, email: String, password: String) = viewModelScope.launch {
        if (name.isBlank() || !email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            notify("Enter a name and a valid email address")
            return@launch
        }
        if (password.length < 8) {
            notify("Password must be at least 8 characters")
            return@launch
        }
        if (users.findByEmail(email.trim()) != null) {
            notify("An account with this email already exists")
            return@launch
        }
        val (salt, hash) = PasswordHasher.createHash(password)
        val id = users.insert(UserEntity(name = name.trim(), email = email.trim(), passwordHash = hash, passwordSalt = salt))
        session.signInUser(id)
        refreshUser()
        startDeviceSync()
        _state.value = _state.value.copy(screen = AppScreen.HOME, message = "Account created. Waiting for administrator approval.")
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        if (!loginLimiter.allow()) {
            notify("Too many attempts. Try again in a minute.")
            return@launch
        }
        val user = users.findByEmail(email.trim())
        if (user == null || !PasswordHasher.matches(password, user.passwordSalt, user.passwordHash)) {
            notify("Invalid email or password")
            return@launch
        }
        session.signInUser(user.id)
        refreshUser()
        startDeviceSync()
        _state.value = _state.value.copy(screen = AppScreen.HOME)
    }

    fun logout() {
        session.signOut()
        deviceSyncJob?.cancel()
        deviceSyncJob = null
        TestAutomationService.setRunning(false)
        _state.value = UiState(adminConfigured = _state.value.adminConfigured)
    }

    fun setupAdmin(email: String, password: String) = viewModelScope.launch {
        if (admins.find() != null) {
            notify("Administrator is already initialized")
            show(AppScreen.ADMIN_LOGIN)
            return@launch
        }
        if (email.trim().lowercase() != "diwanatik84@gmail.com") {
            notify("Use the designated administrator email")
            return@launch
        }
        if (password.length < 12) {
            notify("Use an administrator password of at least 12 characters")
            return@launch
        }
        val (salt, hash) = PasswordHasher.createHash(password)
        admins.save(AdminEntity(email = email.trim(), passwordHash = hash, passwordSalt = salt))
        _state.value = _state.value.copy(adminConfigured = true, screen = AppScreen.ADMIN_LOGIN, message = "Administrator initialized securely on this device.")
    }

    fun adminLogin(email: String, password: String) = viewModelScope.launch {
        val admin = admins.find()
        if (admin == null) {
            show(AppScreen.ADMIN_SETUP)
            return@launch
        }
        if (!loginLimiter.allow() || admin.email.lowercase() != email.trim().lowercase() ||
            !PasswordHasher.matches(password, admin.passwordSalt, admin.passwordHash)
        ) {
            notify("Invalid administrator credentials")
            return@launch
        }
        session.beginAdminSession()
        show(AppScreen.ADMIN_DASHBOARD)
    }

    fun approve(user: UserEntity) {
        if (requireAdminSession()) updateUser(user.copy(accountStatus = AccountStatus.APPROVED))
    }

    fun reject(user: UserEntity) {
        if (requireAdminSession()) updateUser(user.copy(accountStatus = AccountStatus.REJECTED))
    }

    fun disable(user: UserEntity) {
        if (requireAdminSession()) updateUser(user.copy(accountStatus = AccountStatus.DISABLED))
    }

    fun reEnable(user: UserEntity) {
        if (requireAdminSession()) updateUser(user.copy(accountStatus = AccountStatus.APPROVED))
    }

    fun assignSubscription(user: UserEntity, type: SubscriptionType, customDays: Int? = null) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        val start = System.currentTimeMillis()
        updateUser(
            user.copy(
                accountStatus = AccountStatus.APPROVED,
                subscriptionType = type,
                subscriptionStart = start,
                subscriptionExpiry = SubscriptionManager.expiryFor(type, customDays, start),
                updatedAt = start,
            ),
        )
        notify("Subscription updated for ${user.email}")
    }

    fun importTemplate(filename: String, name: String, threshold: Float) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        if (name.isBlank()) {
            notify("Give the template a name")
            return@launch
        }
        templates.insert(TemplateEntity(name = name.trim(), internalFilename = filename, threshold = threshold))
        notify("Template stored privately")
    }

    fun deleteTemplate(template: TemplateEntity) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        templateStore.delete(template.internalFilename)
        templates.delete(template.id)
        notify("Template deleted")
    }

    fun updateTemplate(template: TemplateEntity) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        templates.update(template.copy(updatedAt = System.currentTimeMillis()))
        notify("Template settings saved")
    }

    fun updatePermissionStatus() {
        _state.value = _state.value.copy(permissionStatus = PermissionCenter.read(getApplication()))
    }

    fun startDetection() = viewModelScope.launch {
        val user = _state.value.user ?: return@launch
        if (!SubscriptionManager.isActive(user)) {
            notify("An approved account with an active subscription is required")
            return@launch
        }
        updatePermissionStatus()
        if (!_state.value.permissionStatus.allGranted) {
            show(AppScreen.PERMISSIONS)
            notify("Grant the required permissions before starting")
            return@launch
        }
        val template = templates.findEnabled()
        if (template == null) {
            notify("An administrator must enable a template first")
            return@launch
        }
        val privateFile = runCatching { templateStore.open(template.internalFilename) }.getOrNull()
        if (privateFile == null || !privateFile.exists()) {
            notify("The configured private template is unavailable")
            return@launch
        }
        TestAutomationService.configure(
            privateFile.absolutePath,
            template.threshold,
            _state.value.action,
            user.id,
            template.name,
        )
        TestAutomationService.setRunning(true)
        _state.value = _state.value.copy(
            screen = AppScreen.DETECTION,
            detectionStatus = DetectionStatus(DetectionState.SEARCHING, message = "Running only while you have enabled it"),
        )
    }

    fun stopDetection() {
        TestAutomationService.setRunning(false)
        _state.value = _state.value.copy(
            screen = AppScreen.HOME,
            detectionStatus = DetectionStatus(DetectionState.STOPPED, message = "Stopped by the user"),
        )
    }

    fun pauseDetection() {
        TestAutomationService.setRunning(false)
        _state.value = _state.value.copy(
            detectionStatus = DetectionStatus(DetectionState.IDLE, message = "Paused. Press Start to resume."),
        )
    }

    fun setAction(action: TestAction) {
        _state.value = _state.value.copy(action = action)
    }

    private fun startDeviceSync() {
        deviceSyncJob?.cancel()
        deviceSyncJob = viewModelScope.launch {
            while (isActive) {
                val result = deviceSync.heartbeat(
                    isRunning = TestAutomationService.running,
                    action = _state.value.action,
                    batteryLevel = batteryLevel(),
                )
                result?.commands?.forEach { command ->
                    when (command.type) {
                        "START" -> {
                            setAction(command.action)
                            startDetection()
                        }
                        "STOP" -> stopDetection()
                        "PAUSE" -> pauseDetection()
                        "REFRESH_STATUS" -> Unit
                    }
                }
                delay(20_000L)
            }
        }
    }

    private fun batteryLevel(): Int? {
        val manager = getApplication<Application>().getSystemService(BatteryManager::class.java)
        return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }

    private fun updateUser(user: UserEntity) = viewModelScope.launch {
        users.update(user.copy(updatedAt = System.currentTimeMillis()))
        if (user.id == session.currentUserId()) refreshUser()
    }

    private suspend fun refreshUser() {
        val current = session.currentUserId()?.let { users.findById(it) }
        _state.value = _state.value.copy(user = current)
    }

    private fun notify(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    private fun requireAdminSession(): Boolean {
        if (session.isAdminSessionActive()) return true
        _state.value = _state.value.copy(
            screen = AppScreen.ADMIN_LOGIN,
            message = "Administrator sign-in is required",
        )
        return false
    }

    private fun AppScreen.isAdminProtected(): Boolean =
        this == AppScreen.ADMIN_DASHBOARD || this == AppScreen.USERS ||
            this == AppScreen.TEMPLATES || this == AppScreen.HISTORY
}