package com.replit.jalwa

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.replit.jalwa.capture.ScreenCaptureForegroundService
import com.replit.jalwa.data.AccountStatus
import com.replit.jalwa.data.AdminEntity
import com.replit.jalwa.data.AppDatabase
import com.replit.jalwa.data.ClickHistoryEntity
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

enum class AppScreen {
    LOGIN,
    SIGNUP,
    HOME,
    PERMISSIONS,
    DETECTION,
    ACCOUNT,
    SUBSCRIPTION,
    SETTINGS,
    PRIVACY,
    LICENSES,
    ADMIN_LOGIN,
    ADMIN_SETUP,
    ADMIN_DASHBOARD,
    USERS,
    TEMPLATES,
    HISTORY,
}

data class CaptureLaunch(
    val templateId: Long,
    val templatePath: String,
    val threshold: Float,
    val detectionRegion: String?,
    val action: TestAction,
    val userId: Long,
    val templateName: String,
)

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
    private var pendingCapture: CaptureLaunch? = null
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

    fun showMessage(message: String) {
        notify(message)
    }

    fun signup(name: String, email: String, password: String) = viewModelScope.launch {
        val normalizedEmail = email.trim().lowercase()
        if (name.isBlank() || !normalizedEmail.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            notify("Enter a name and a valid email address")
            return@launch
        }
        if (password.length < 8) {
            notify("Password must be at least 8 characters")
            return@launch
        }
        if (users.findByEmail(normalizedEmail) != null) {
            notify("An account with this email already exists")
            return@launch
        }
        val (salt, hash) = PasswordHasher.createHash(password)
        val id = runCatching {
            users.insert(
                UserEntity(
                    name = name.trim(),
                    email = normalizedEmail,
                    passwordHash = hash,
                    passwordSalt = salt,
                ),
            )
        }.getOrElse {
            notify("An account with this email already exists")
            return@launch
        }
        session.signInUser(id)
        refreshUser()
        _state.value = _state.value.copy(screen = AppScreen.HOME, message = "Account created. Waiting for administrator approval.")
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        if (!loginLimiter.allow()) {
            notify("Too many attempts. Try again in a minute.")
            return@launch
        }
        val user = users.findByEmail(email.trim().lowercase())
        if (user == null || !PasswordHasher.matches(password, user.passwordSalt, user.passwordHash)) {
            notify("Invalid email or password")
            return@launch
        }
        if (PasswordHasher.needsRehash(user.passwordSalt)) {
            val (salt, hash) = PasswordHasher.createHash(password)
            users.update(user.copy(passwordSalt = salt, passwordHash = hash))
        }
        session.signInUser(user.id)
        refreshUser()
        _state.value = _state.value.copy(screen = AppScreen.HOME)
    }

    fun logout() {
        session.signOut()
        ScreenCaptureForegroundService.stop(getApplication())
        pendingCapture = null
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
        admins.save(AdminEntity(email = email.trim().lowercase(), passwordHash = hash, passwordSalt = salt))
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
        if (PasswordHasher.needsRehash(admin.passwordSalt)) {
            val (salt, hash) = PasswordHasher.createHash(password)
            admins.save(admin.copy(passwordSalt = salt, passwordHash = hash))
        }
        session.beginAdminSession(admin.id)
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

    fun importTemplate(
        filename: String,
        name: String,
        threshold: Float,
        detectionRegion: String? = null,
    ) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        if (name.isBlank()) {
            notify("Give the template a name")
            return@launch
        }
        templates.insert(
            TemplateEntity(
                name = name.trim(),
                internalFilename = filename,
                threshold = threshold,
                detectionRegion = detectionRegion?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        notify("Template stored privately")
    }

    fun deleteTemplate(template: TemplateEntity) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        templateStore.delete(template.internalFilename)
        templates.delete(template.id)
        notify("Template deleted")
    }

    fun replaceTemplate(template: TemplateEntity, uri: android.net.Uri) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        runCatching { templateStore.replace(uri, template.internalFilename) }
            .onSuccess { notify("Template replaced privately") }
            .onFailure { notify("The replacement image is invalid") }
    }

    fun updateTemplate(template: TemplateEntity) = viewModelScope.launch {
        if (!requireAdminSession()) return@launch
        templates.update(template.copy(updatedAt = System.currentTimeMillis()))
        notify("Template settings saved")
    }

    fun updatePermissionStatus() {
        _state.value = _state.value.copy(permissionStatus = PermissionCenter.read(getApplication()))
    }

    fun prepareDetection(onReady: () -> Unit) = viewModelScope.launch {
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
        pendingCapture = CaptureLaunch(
            templateId = template.id,
            templatePath = privateFile.absolutePath,
            threshold = template.threshold,
            detectionRegion = template.detectionRegion,
            action = _state.value.action,
            userId = user.id,
            templateName = template.name,
        )
        onReady()
    }

    fun startDetection(permissionResultCode: Int, permissionData: Intent) {
        val launch = pendingCapture ?: run {
            notify("Start the detector from the home screen")
            return
        }
        ScreenCaptureForegroundService.start(
            context = getApplication(),
            permissionResultCode = permissionResultCode,
            permissionData = permissionData,
            templatePath = launch.templatePath,
            templateId = launch.templateId,
            threshold = launch.threshold,
            detectionRegion = launch.detectionRegion,
            action = launch.action,
            userId = launch.userId,
            templateName = launch.templateName,
        )
        pendingCapture = null
        _state.value = _state.value.copy(
            screen = AppScreen.DETECTION,
            detectionStatus = DetectionStatus(
                DetectionState.CAPTURING,
                message = "Screen capture is active with your consent",
            ),
        )
    }

    fun stopDetection() {
        ScreenCaptureForegroundService.stop(getApplication())
        _state.value = _state.value.copy(
            screen = AppScreen.HOME,
            detectionStatus = DetectionStatus(DetectionState.STOPPED, message = "Stopped by the user"),
        )
    }

    fun pauseDetection() {
        ScreenCaptureForegroundService.pause(getApplication())
        _state.value = _state.value.copy(
            detectionStatus = DetectionStatus(DetectionState.PAUSED, message = "Paused"),
        )
    }

    fun resumeDetection() {
        ScreenCaptureForegroundService.resume(getApplication())
        _state.value = _state.value.copy(
            detectionStatus = DetectionStatus(DetectionState.SEARCHING, message = "Searching in memory"),
        )
    }

    fun setAction(action: TestAction) {
        _state.value = _state.value.copy(action = action)
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