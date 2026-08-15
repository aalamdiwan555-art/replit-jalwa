package com.replit.jalwa.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAndSubscriptionTest {
    @Test
    fun passwords_are_hashed_and_match_only_original_password() {
        val (salt, hash) = PasswordHasher.createHash("correct horse battery")
        assertFalse(hash.contains("correct"))
        assertTrue(PasswordHasher.matches("correct horse battery", salt, hash))
        assertFalse(PasswordHasher.matches("wrong password", salt, hash))
    }

    @Test
    fun subscriptions_use_expiry_timestamps() {
        val start = 1_000_000L
        val user = UserEntity(
            name = "Test",
            email = "test@example.com",
            passwordHash = "hash",
            passwordSalt = "salt",
            accountStatus = AccountStatus.APPROVED,
            subscriptionType = SubscriptionType.ONE_DAY,
            subscriptionStart = start,
            subscriptionExpiry = SubscriptionManager.expiryFor(SubscriptionType.ONE_DAY, start = start),
        )
        assertTrue(SubscriptionManager.isActive(user, start + 10))
        assertFalse(SubscriptionManager.isActive(user, start + 86_400_001))
    }

    @Test
    fun lifetime_has_no_expiry() {
        val start = 1_000_000L
        assertTrue(SubscriptionManager.expiryFor(SubscriptionType.LIFETIME, start = start) == null)
    }
}