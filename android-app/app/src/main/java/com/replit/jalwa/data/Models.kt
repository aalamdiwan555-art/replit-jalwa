package com.replit.jalwa.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AccountStatus { PENDING, APPROVED, REJECTED, DISABLED, EXPIRED }
enum class SubscriptionType { NONE, ONE_DAY, TWO_DAYS, THREE_DAYS, CUSTOM, LIFETIME }

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)],
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String,
    val passwordHash: String,
    val passwordSalt: String,
    val accountStatus: AccountStatus = AccountStatus.PENDING,
    val subscriptionType: SubscriptionType = SubscriptionType.NONE,
    val subscriptionStart: Long? = null,
    val subscriptionExpiry: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "admins")
data class AdminEntity(
    @PrimaryKey val id: Int = 1,
    val email: String,
    val passwordHash: String,
    val passwordSalt: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val internalFilename: String,
    val enabled: Boolean = true,
    val threshold: Float = 0.90f,
    val detectionRegion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "click_history",
    indices = [Index("userId"), Index("occurredAt")],
)
data class ClickHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val templateName: String,
    val action: String,
    val confidence: Float,
    val occurredAt: Long = System.currentTimeMillis(),
)

data class UserSummary(
    val total: Int,
    val pending: Int,
    val approved: Int,
    val rejected: Int,
    val expired: Int,
    val activeSubscriptions: Int,
)