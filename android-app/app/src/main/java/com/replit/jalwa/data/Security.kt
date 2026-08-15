package com.replit.jalwa.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {
    private const val SALT_BYTES = 16

    fun createHash(password: String): Pair<String, String> {
        require(password.length >= 8) { "Password must be at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return encode(salt) to encode(digest(password, salt))
    }

    fun matches(password: String, salt: String, hash: String): Boolean =
        MessageDigest.isEqual(
            digest(password, decode(salt)),
            decode(hash),
        )

    private fun digest(password: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var result = digest.digest(salt + password.toByteArray(Charsets.UTF_8))
        repeat(120_000) {
            result = digest.digest(result + salt + password.toByteArray(Charsets.UTF_8))
        }
        return result
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}

class RateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000,
) {
    private val attempts = mutableListOf<Long>()

    @Synchronized
    fun allow(now: Long = System.currentTimeMillis()): Boolean {
        attempts.removeAll { now - it > windowMillis }
        if (attempts.size >= maxAttempts) return false
        attempts += now
        return true
    }
}

class SessionManager {
    private var userId: Long? = null
    private var adminExpiresAt: Long? = null

    fun signInUser(id: Long) {
        userId = id
    }

    fun signOut() {
        userId = null
        adminExpiresAt = null
    }

    fun currentUserId(): Long? = userId

    fun beginAdminSession(durationMillis: Long = 15 * 60_000L) {
        adminExpiresAt = System.currentTimeMillis() + durationMillis
    }

    fun isAdminSessionActive(): Boolean =
        adminExpiresAt?.let { it > System.currentTimeMillis() } == true
}