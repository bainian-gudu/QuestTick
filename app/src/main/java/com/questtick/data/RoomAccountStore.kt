package com.questtick.data

import android.content.Context
import android.util.Log
import com.questtick.data.db.AccountEntity
import com.questtick.data.db.AppDatabase
import org.json.JSONObject

/** Room 版账号存储；敏感字段以 Keystore AES-GCM 加密 JSON payload 保存。 */
internal class RoomAccountStore(
    context: Context,
) {
    private val dao = AppDatabase.get(context).accountDao()
    private val lock = Any()

    @Volatile private var cache: List<Account>? = null
    @Volatile private var recoveryIssuesCache: List<AccountRecoveryIssue> = emptyList()

    fun get(): List<Account> =
        synchronized(lock) {
            cache?.let { return@synchronized it }
            val accounts = mutableListOf<Account>()
            val issues = mutableListOf<AccountRecoveryIssue>()
            dao.getAll().forEach { entity ->
                when (val decoded = entity.decode()) {
                    is DecodedAccount.Valid -> accounts += decoded.account
                    is DecodedAccount.Invalid -> issues += decoded.issue
                }
            }
            cache = accounts
            recoveryIssuesCache = issues
            accounts
        }

    fun getRecoveryIssues(): List<AccountRecoveryIssue> =
        synchronized(lock) {
            get()
            recoveryIssuesCache
        }

    fun upsert(account: Account) =
        synchronized(lock) {
            val current = get().toMutableList()
            val idx = current.indexOfFirst { it.id == account.id }
            val sortOrder = if (idx >= 0) idx else current.size
            val existing = if (idx >= 0) dao.getById(account.id) else null
            if (idx >= 0) current[idx] = account else current.add(account)
            dao.upsert(account.toEntity(sortOrder, existing = existing))
            cache = current.toList()
            recoveryIssuesCache = recoveryIssuesCache.filterNot { it.accountId == account.id }
        }

    /** 基于最新账号快照执行原子更新，防止自动刷新覆盖用户刚保存的其它字段。 */
    fun update(
        id: String,
        transform: (Account) -> Account,
    ): Account? =
        synchronized(lock) {
            val current = get().toMutableList()
            val idx = current.indexOfFirst { it.id == id }
            if (idx < 0) return@synchronized null
            val updated = transform(current[idx])
            require(updated.id == id) { "Account id cannot be changed during update" }
            val existing = dao.getById(id)
            current[idx] = updated
            dao.upsert(updated.toEntity(idx, existing = existing))
            cache = current.toList()
            recoveryIssuesCache = recoveryIssuesCache.filterNot { it.accountId == id }
            updated
        }

    fun delete(id: String) =
        synchronized(lock) {
            dao.delete(id)
            cache = get().filterNot { it.id == id }
            recoveryIssuesCache = recoveryIssuesCache.filterNot { it.accountId == id }
            // 重写剩余 sortOrder，保证列表顺序稳定；纯重排不刷新账号时间戳。
            cache.orEmpty().forEachIndexed { index, account ->
                dao.upsert(account.toEntity(index, existing = dao.getById(account.id), touchUpdatedAt = false))
            }
        }

    private fun AccountEntity.decode(): DecodedAccount {
        val plain = CryptoStore.decryptToString(payloadCipher)
            ?: return DecodedAccount.Invalid(
                AccountRecoveryIssue(id, label, AccountRecoveryIssue.Reason.DECRYPTION_FAILED),
            )
        return try {
            DecodedAccount.Valid(Account.fromJson(JSONObject(plain)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse account payload $id: ${e::class.java.simpleName}")
            DecodedAccount.Invalid(
                AccountRecoveryIssue(id, label, AccountRecoveryIssue.Reason.INVALID_FORMAT),
            )
        }
    }

    private sealed interface DecodedAccount {
        data class Valid(val account: Account) : DecodedAccount
        data class Invalid(val issue: AccountRecoveryIssue) : DecodedAccount
    }

    private fun Account.toEntity(
        sortOrder: Int,
        existing: AccountEntity? = null,
        touchUpdatedAt: Boolean = true,
    ): AccountEntity {
        val now = System.currentTimeMillis()
        return AccountEntity(
            id = id,
            label = label,
            enabled = enabled,
            payloadCipher = CryptoStore.encryptString(toJson().toString()),
            sortOrder = sortOrder,
            createdAt = existing?.createdAt ?: now,
            updatedAt = if (touchUpdatedAt) now else existing?.updatedAt ?: now,
        )
    }

    companion object {
        private const val TAG = "RoomAccountStore"
    }
}
