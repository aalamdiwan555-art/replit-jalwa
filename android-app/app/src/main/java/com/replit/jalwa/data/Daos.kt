package com.replit.jalwa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): UserEntity?

    @Query("SELECT * FROM users WHERE lower(email) = lower(:email) LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity): Long

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM users WHERE accountStatus = :status")
    suspend fun countByStatus(status: AccountStatus): Int

    @Query("SELECT COUNT(*) FROM users WHERE subscriptionExpiry IS NULL AND subscriptionType = 'LIFETIME' OR subscriptionExpiry > :now")
    suspend fun countActiveSubscriptions(now: Long): Int
}

@Dao
interface AdminDao {
    @Query("SELECT * FROM admins WHERE id = 1 LIMIT 1")
    suspend fun find(): AdminEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(admin: AdminEntity)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE enabled = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun findEnabled(): TemplateEntity?

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int

    @Insert
    suspend fun insert(template: TemplateEntity): Long

    @Update
    suspend fun update(template: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ClickHistoryDao {
    @Query("SELECT * FROM click_history ORDER BY occurredAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<ClickHistoryEntity>>

    @Insert
    suspend fun insert(event: ClickHistoryEntity)
}