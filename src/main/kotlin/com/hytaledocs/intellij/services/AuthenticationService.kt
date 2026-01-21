package com.hytaledocs.intellij.services

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Centralized authentication service for Hytale server and downloader.
 * Handles OAuth device code flow with proper state management and debouncing.
 */
@Service(Service.Level.APP)
class AuthenticationService {

    companion object {
        private val LOG = Logger.getInstance(AuthenticationService::class.java)

        const val VERIFICATION_URL = "https://oauth.accounts.hytale.com/oauth2/device/verify"

        // Debounce window: ignore duplicate codes within this period
        private const val DEBOUNCE_MS = 5000L

        // Delay before triggering auth command (allow server to fully initialize)
        private const val AUTH_TRIGGER_DELAY_MS = 1500L

        fun getInstance(): AuthenticationService {
            return ApplicationManager.getApplication().getService(AuthenticationService::class.java)
        }
    }

    /**
     * Current authentication session state.
     */
    enum class AuthState {
        IDLE,
        AWAITING_CODE,
        CODE_DISPLAYED,
        AUTHENTICATING,
        SUCCESS,
        FAILED
    }

    /**
     * Source of the authentication request.
     */
    enum class AuthSource {
        SERVER,
        DOWNLOADER
    }

    /**
     * Authentication session data.
     */
    data class AuthSession(
        val source: AuthSource,
        val deviceCode: String,
        val verificationUrl: String,
        val state: AuthState,
        val createdAt: Instant = Instant.now(),
        val message: String? = null
    )

    /**
     * Callback for authentication state changes.
     */
    data class AuthCallback(
        val project: Project?,
        val onStateChange: Consumer<AuthSession>
    )

    // Current session
    private val currentSession = AtomicReference<AuthSession?>(null)

    // Last code timestamp for debouncing
    private var lastCodeTime: Instant = Instant.MIN
    private var lastCode: String? = null

    // Registered callbacks
    private val callbacks = CopyOnWriteArrayList<AuthCallback>()

    // Browser opened flag to prevent multiple browser tabs
    private var browserOpenedForSession: String? = null

    /**
     * Register a callback to receive authentication state changes.
     */
    fun registerCallback(project: Project?, callback: Consumer<AuthSession>) {
        callbacks.add(AuthCallback(project, callback))
    }

    /**
     * Unregister callbacks for a specific project.
     */
    fun unregisterCallbacks(project: Project?) {
        callbacks.removeIf { it.project == project }
    }

    /**
     * Get the current authentication session.
     */
    fun getCurrentSession(): AuthSession? = currentSession.get()

    /**
     * Check if currently authenticating.
     */
    fun isAuthenticating(): Boolean {
        val session = currentSession.get() ?: return false
        return session.state in listOf(AuthState.AWAITING_CODE, AuthState.CODE_DISPLAYED, AuthState.AUTHENTICATING)
    }

    /**
     * Parse a log line for authentication messages.
     * Call this from ServerLaunchService for each log line.
     * Returns true if the line was auth-related.
     */
    fun parseServerLogLine(line: String, project: Project?): Boolean {
        // Detect auth required - ONLY on the specific WARN message from HytaleServer (not ServerAuthManager INFO)
        // Authentication can be persisted with /auth persistence Encrypted, so we only prompt once
        // Log format: [2026/01/17 20:01:43   WARN]                   [HytaleServer] No server tokens configured...
        if (line.contains("WARN") && line.contains("[HytaleServer]") && line.contains("No server tokens configured")) {
            LOG.info("[HytaleDocs] Auth required detected: $line")

            // Only start session and trigger auth if not already authenticating
            if (!isAuthenticating()) {
                startSession(AuthSource.SERVER, project)
                // Auto-trigger the auth command with longer delay to ensure server is fully ready
                if (project != null) {
                    LOG.info("[HytaleDocs] Auto-triggering /auth login device (waiting ${AUTH_TRIGGER_DELAY_MS}ms)")
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(AUTH_TRIGGER_DELAY_MS)
                        if (isAuthenticating()) {
                            LOG.info("[HytaleDocs] Sending /auth login device command")
                            triggerServerAuth(project)
                        }
                    }
                }
            }
            return true
        }

        // Detect the URL with user_code parameter (preferred - has code embedded)
        val urlWithCodePattern = Regex("""(https?://[^\s]*[?&]user_code=([A-Za-z0-9]+))""")
        urlWithCodePattern.find(line)?.let { match ->
            val fullUrl = match.groupValues[1]
            val code = match.groupValues[2]
            LOG.info("[HytaleDocs] Device code from URL: $code")
            handleDeviceCode(code, fullUrl, AuthSource.SERVER, project)
            return true
        }

        // Detect device code from various formats
        val codePatterns = listOf(
            Regex("""Enter code:\s*([A-Za-z0-9-]+)"""),
            Regex("""code[:\s]+([A-Za-z0-9-]{6,})""", RegexOption.IGNORE_CASE),
            Regex("""device code[:\s]+([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
            Regex("""verification code[:\s]+([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
            Regex("""user_code[:\s=]+([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
            Regex("""Your code is[:\s]+([A-Za-z0-9-]+)""", RegexOption.IGNORE_CASE),
            Regex("""\bcode:\s*([A-Z0-9-]{6,12})\b""")
        )

        for (pattern in codePatterns) {
            pattern.find(line)?.let { match ->
                val code = match.groupValues[1]
                if (code.length >= 6) {
                    val url = "$VERIFICATION_URL?user_code=$code"
                    LOG.info("[HytaleDocs] Device code detected: $code")
                    handleDeviceCode(code, url, AuthSource.SERVER, project)
                    return true
                }
            }
        }

        // Detect auth success - ONLY if we are currently authenticating (have shown a code)
        // This prevents false positives from server boot messages
        if (isAuthenticating()) {
            val successPatterns = listOf(
                "Authentication successful",
                "Successfully authenticated",
                "Server authenticated",
                "Authorization successful",
                "Logged in as",
                "authenticated successfully",
                "authentication complete",
                "server is now authenticated",
                "tokens configured",
                "token saved",
                "credentials saved"
            )

            if (successPatterns.any { line.contains(it, ignoreCase = true) }) {
                LOG.info("[HytaleDocs] Auth success detected: $line")
                handleAuthSuccess(AuthSource.SERVER, project)
                return true
            }
        }

        // Detect auth failure
        val failurePatterns = listOf(
            "Authentication failed",
            "Auth failed",
            "Failed to authenticate",
            "Authorization failed",
            "Authentication timed out",
            "authentication error"
        )

        if (failurePatterns.any { line.contains(it, ignoreCase = true) }) {
            LOG.info("[HytaleDocs] Auth failure detected: $line")
            handleAuthFailed(line, AuthSource.SERVER, project)
            return true
        }

        return false
    }

    /**
     * Parse downloader output for authentication messages.
     */
    fun parseDownloaderLine(line: String, project: Project?): Boolean {
        val codeRegex = """(?i)code[:\s]+([A-Z0-9-]{6,})""".toRegex()

        codeRegex.find(line)?.let { match ->
            val code = match.groupValues[1]
            if (code.length >= 6) {
                val url = "$VERIFICATION_URL?user_code=$code"
                handleDeviceCode(code, url, AuthSource.DOWNLOADER, project)
                return true
            }
        }

        if (line.contains("authenticated", ignoreCase = true)) {
            handleAuthSuccess(AuthSource.DOWNLOADER, project)
            return true
        }

        return false
    }

    /**
     * Start a new authentication session.
     */
    private fun startSession(source: AuthSource, project: Project?) {
        val session = AuthSession(
            source = source,
            deviceCode = "",
            verificationUrl = VERIFICATION_URL,
            state = AuthState.AWAITING_CODE
        )

        currentSession.set(session)
        browserOpenedForSession = null
        notifyCallbacks(session)
    }

    /**
     * Handle device code detection with debouncing.
     */
    private fun handleDeviceCode(code: String, url: String, source: AuthSource, project: Project?) {
        val now = Instant.now()

        // Debounce: ignore if same code within debounce window
        if (code == lastCode && now.toEpochMilli() - lastCodeTime.toEpochMilli() < DEBOUNCE_MS) {
            return
        }

        // Also ignore if we already have this code in the current session
        val existingSession = currentSession.get()
        if (existingSession != null && existingSession.deviceCode == code &&
            existingSession.state == AuthState.CODE_DISPLAYED) {
            return
        }

        lastCode = code
        lastCodeTime = now

        val session = AuthSession(
            source = source,
            deviceCode = code,
            verificationUrl = url,
            state = AuthState.CODE_DISPLAYED
        )

        currentSession.set(session)
        notifyCallbacks(session)

        // Open browser only once per session
        if (browserOpenedForSession != code) {
            browserOpenedForSession = code
            ApplicationManager.getApplication().invokeLater {
                BrowserUtil.browse(url)
            }
        }

        // Show notification
        showNotification(
            project,
            "Authentication Required",
            "Enter code $code in the browser window",
            NotificationType.WARNING
        )
    }

    /**
     * Handle authentication success.
     */
    private fun handleAuthSuccess(source: AuthSource, project: Project?) {
        val existingSession = currentSession.get()

        val session = AuthSession(
            source = source,
            deviceCode = existingSession?.deviceCode ?: "",
            verificationUrl = existingSession?.verificationUrl ?: VERIFICATION_URL,
            state = AuthState.SUCCESS,
            message = "Authentication successful!"
        )

        currentSession.set(session)
        browserOpenedForSession = null
        lastCode = null
        notifyCallbacks(session)

        showNotification(
            project,
            "Authentication Successful",
            "Server is now authenticated",
            NotificationType.INFORMATION
        )

        // Persist authentication so we don't need to re-authenticate on next server start
        if (source == AuthSource.SERVER && project != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(1000) // Wait a bit for auth to be fully processed
                val launchService = ServerLaunchService.getInstance(project)
                if (launchService.isServerRunning()) {
                    LOG.info("[HytaleDocs] Persisting authentication with /auth persistence Encrypted")
                    launchService.sendCommand("/auth persistence Encrypted")
                }
            }
        }

        // Clear session after a delay
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(3000)
            if (currentSession.get()?.state == AuthState.SUCCESS) {
                currentSession.set(null)
            }
        }
    }

    /**
     * Handle authentication failure.
     */
    private fun handleAuthFailed(message: String, source: AuthSource, project: Project?) {
        val existingSession = currentSession.get()

        val session = AuthSession(
            source = source,
            deviceCode = existingSession?.deviceCode ?: "",
            verificationUrl = existingSession?.verificationUrl ?: VERIFICATION_URL,
            state = AuthState.FAILED,
            message = message
        )

        currentSession.set(session)
        browserOpenedForSession = null
        lastCode = null
        notifyCallbacks(session)

        showNotification(
            project,
            "Authentication Failed",
            "Please try again",
            NotificationType.ERROR
        )

        // Clear session after a delay
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(5000)
            if (currentSession.get()?.state == AuthState.FAILED) {
                currentSession.set(null)
            }
        }
    }

    /**
     * Reset the current session.
     */
    fun resetSession() {
        currentSession.set(null)
        browserOpenedForSession = null
        lastCode = null
        lastCodeTime = Instant.MIN
    }

    /**
     * Manually trigger server authentication.
     */
    fun triggerServerAuth(project: Project): Boolean {
        val launchService = ServerLaunchService.getInstance(project)
        if (launchService.isServerRunning()) {
            startSession(AuthSource.SERVER, project)
            return launchService.sendCommand("/auth login device")
        }
        return false
    }

    /**
     * Notify all registered callbacks of state change.
     */
    private fun notifyCallbacks(session: AuthSession) {
        ApplicationManager.getApplication().invokeLater {
            callbacks.forEach { callback ->
                try {
                    callback.onStateChange.accept(session)
                } catch (e: Exception) {
                    LOG.warn("Error notifying auth callback for project ${callback.project?.name}", e)
                }
            }
        }
    }

    /**
     * Show a notification.
     */
    private fun showNotification(project: Project?, title: String, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Hytale Plugin")
                .createNotification(title, content, type)
                .notify(project)
        } catch (e: Exception) {
            LOG.warn("Failed to show notification: $title - $content", e)
        }
    }
}
