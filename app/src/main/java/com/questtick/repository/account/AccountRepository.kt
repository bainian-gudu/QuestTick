package com.questtick.repository.account

import com.questtick.data.Account
import com.questtick.data.AccountRecoveryIssue
import com.questtick.data.SecureStore
import com.questtick.repository.base.StatefulRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 账号仓库：负责账号增删改查、Cookie 读取与 UID 登录凭证管理。 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val store: SecureStore,
    ) : StatefulRepository<List<Account>>(emptyList(), List<Account>::isEmpty) {
        val accounts: StateFlow<List<Account>> = state

        private val _recoveryIssues = MutableStateFlow<List<AccountRecoveryIssue>>(emptyList())
        val recoveryIssues: StateFlow<List<AccountRecoveryIssue>> = _recoveryIssues.asStateFlow()

        override suspend fun loadFromStore(): List<Account> {
            return withContext(kotlinx.coroutines.Dispatchers.IO) {
                store.getAccounts().also {
                    _recoveryIssues.value = store.getAccountRecoveryIssues()
                }
            }
        }

        override suspend fun saveToStore(data: List<Account>) {
            // 账号更新通过 upsert/update 单独处理；StatefulRepository.updateAndPersist 不应被调用
            throw UnsupportedOperationException("AccountRepository.saveToStore is not supported; use upsert()/update()")
        }

        override suspend fun reload() {
            super.reload()
        }

        suspend fun getAccounts(): List<Account> = loadFromStore()

        suspend fun upsert(account: Account) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.upsertAccount(account) }
            reload()
        }

        suspend fun update(
            id: String,
            transform: (Account) -> Account,
        ): Account? {
            val updated = withContext(kotlinx.coroutines.Dispatchers.IO) { store.updateAccount(id, transform) }
            reload()
            return updated
        }

        suspend fun delete(id: String) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.deleteAccount(id) }
            reload()
        }

        /** 删除无法解密或解析的账号密文；不提供密文导出，避免扩大凭证泄露面。 */
        suspend fun deleteRecoveryIssue(accountId: String) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { store.deleteAccount(accountId) }
            reload()
        }

        suspend fun cookieOf(accountId: String): String =
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                store.getAccounts().firstOrNull { it.id == accountId }?.mysCookie.orEmpty()
            }

        suspend fun updateUidCredentials(
            accountId: String,
            uid: String,
            stoken: String,
            stmid: String,
            ltoken: String,
            keepLogin: Boolean,
        ): Account? =
            update(accountId) { latest ->
                latest.copy(
                    mysUid = uid,
                    stoken = stoken,
                    stmid = stmid,
                    ltoken = ltoken,
                    qrLoginBound = keepLogin,
                )
            }
    }
