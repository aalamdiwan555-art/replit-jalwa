package com.replit.jalwa.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2_PREFIX = "pbkdf2_sha256$"

    fun createHash(password: String): Pair<String, String> {
        require(password.length >= 8) { "Password must be at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return PBKDF2_PREFIX + encode(salt) to encode(pbkdf2(password, salt))
    }

    fun matches(password: String, salt: String, hash: String): Boolean = runCatching {
        val (actualSalt, actualHash) = if (salt.startsWith(PBKDF2_PREFIX)) {
            decode(salt.removePrefix(PBKDF2_PREFIX)) to pbkdf2(password, decode(salt.removePrefix(PBKDF2_PREFIX)))
        } else {
            decode(salt) to digestLegacy(password, decode(salt))
        }
        MessageDigest.isEqual(actualHash, decode(hash))
    }.getOrDefault(false)

    fun needsRehash(salt: String): Boolean = !salt.startsWith(PBKDF2_PREFIX)

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun digestLegacy(password: String, salt: ByteArray): ByteArray {
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
    private var adminId: Int? = null
    private var adminExpiresAt: Long? = null

    fun signInUser(id: Long) {
        userId = id
    }

    fun signOut() {
        userId = null
        adminId = null
        adminExpiresAt = null
    }

    fun currentUserId(): Long? = userId

    fun beginAdminSession(id: Int, durationMillis: Long = 15 * 60_000L) {
        adminId = id
        adminExpiresAt = System.currentTimeMillis() + durationMillis
    }

    fun isAdminSessionActive(id: Int = 1): Boolean =
        adminId == id && adminExpiresAt?.let { it > System.currentTimeMillis() } == true
}