package com.replit.jalwa.data

import java.util.concurrent.TimeUnit

object SubscriptionManager {
    fun isActive(user: UserEntity, now: Long = System.currentTimeMillis()): Boolean {
        if (user.accountStatus != AccountStatus.APPROVED) return false
        if (user.subscriptionType == SubscriptionType.LIFETIME) return true
        return user.subscriptionExpiry?.let { it > now } == true
    }

    fun expiryLabel(user: UserEntity, now: Long = System.currentTimeMillis()): String =
        when {
            user.subscriptionType == SubscriptionType.LIFETIME -> "Lifetime"
            user.subscriptionExpiry == null -> "No active subscription"
            user.subscriptionExpiry <= now -> "Expired"
            else -> {
                val remaining = user.subscriptionExpiry - now
                val days = TimeUnit.MILLISECONDS.toDays(remaining)
                val hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24
                if (days > 0) "$days d $hours h remaining" else "$hours h remaining"
            }
        }

    fun expiryFor(type: SubscriptionType, customDays: Int? = null, start: Long): Long? =
        when (type) {
            SubscriptionType.ONE_DAY -> start + TimeUnit.DAYS.toMillis(1)
            SubscriptionType.TWO_DAYS -> start + TimeUnit.DAYS.toMillis(2)
            SubscriptionType.THREE_DAYS -> start + TimeUnit.DAYS.toMillis(3)
            SubscriptionType.CUSTOM -> start + TimeUnit.DAYS.toMillis((customDays ?: 1).toLong())
            SubscriptionType.LIFETIME, SubscriptionType.NONE -> null
        }
}