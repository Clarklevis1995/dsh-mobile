package com.clarklevis.dsh.shared.tailscale

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一个账号对应一个节点身份和状态目录；同一账号的不同 tailnet 可以分别保存。 */
@Serializable
data class TailscaleAccount(
    val id: String,
    val name: String = "",
    val loginName: String? = null,
    val tailnetName: String? = null,
    val nodeName: String = "",
    val authenticated: Boolean = false,
    val usesLegacyState: Boolean = false
) {
    val displayName: String get() = name.ifBlank { loginName.orEmpty().ifBlank { tailnetName.orEmpty() } }
    val stateRelativePath: String get() = if (usesLegacyState) "tsnet-state" else "tsnet-accounts/$id"
}

@Serializable
private data class AccountSnapshot(
    val accounts: List<TailscaleAccount>,
    val selectedID: String? = null,
    val initialAccount: TailscaleAccount? = null
)

/** 不接触设备存储：平台只负责读写一个 JSON 字符串及发布界面变化。 */
class TailscaleAccountCatalog(savedJSON: String?, legacyAccount: TailscaleAccount) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private var initialAccount = legacyAccount.copy(authenticated = false)
    var accounts: List<TailscaleAccount>
        private set
    var selectedID: String?
        private set
    val selectedAccount: TailscaleAccount get() = accounts.firstOrNull { it.id == selectedID } ?: initialAccount

    init {
        val saved = savedJSON?.let { runCatching { json.decodeFromString<AccountSnapshot>(it) }.getOrNull() }
            ?.takeIf { snapshot ->
                snapshot.accounts.map { it.id }.distinct().size == snapshot.accounts.size &&
                    snapshot.accounts.count { it.usesLegacyState } <= 1 &&
                    snapshot.accounts.all { validID(it.id) }
            }
        accounts = saved?.accounts?.filter { it.authenticated }
            ?: listOfNotNull(legacyAccount.takeIf { it.authenticated })
        saved?.initialAccount?.takeIf { validID(it.id) && !it.authenticated && !it.usesLegacyState }
            ?.let { initialAccount = it }
        selectedID = saved?.selectedID?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        deduplicate(preferredID = selectedID)
    }

    fun select(id: String): Boolean {
        if (accounts.none { it.id == id }) return false
        selectedID = id
        return true
    }

    fun rename(id: String, name: String) {
        accounts = accounts.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    /** 删除后为下次登录保留全新目录，避免再次读取被删除账号的旧凭据。 */
    fun remove(id: String, replacementID: String): TailscaleAccount? {
        if (!validID(replacementID) || replacementID == id || accounts.any { it.id == replacementID }) return null
        val removed = accounts.firstOrNull { it.id == id } ?: return null
        accounts = accounts.filterNot { it.id == id }
        if (selectedID == id) selectedID = accounts.firstOrNull()?.id
        initialAccount = TailscaleAccount(id = replacementID)
        return removed
    }

    fun setNodeName(raw: String): Boolean {
        val normalized = if (raw.isBlank()) "" else TailscalePolicy.normalizeNodeName(raw) ?: return false
        val selected = selectedAccount.copy(nodeName = normalized)
        if (accounts.any { it.id == selected.id }) {
            accounts = accounts.map { if (it.id == selected.id) selected else it }
        } else {
            initialAccount = selected
        }
        return true
    }

    fun saveAuthenticated(account: TailscaleAccount, loginName: String?, tailnetName: String?) {
        if (!validID(account.id)) return
        val base = accounts.firstOrNull { it.id == account.id }
            ?: initialAccount.takeIf { it.id == account.id } ?: account
        val saved = base.copy(
            authenticated = true,
            loginName = loginName?.takeIf { it.isNotBlank() } ?: base.loginName,
            tailnetName = tailnetName?.takeIf { it.isNotBlank() } ?: base.tailnetName
        )
        accounts = if (accounts.any { it.id == saved.id }) {
            accounts.map { if (it.id == saved.id) saved else it }
        } else {
            accounts + saved
        }
        selectedID = saved.id
        // 保留刚认证成功节点的 ID 和目录，避免切换后的活动节点与持久化身份不一致。
        deduplicate(preferredID = saved.id)
    }

    fun markAuthenticated(id: String, loginName: String?, tailnetName: String?) {
        if (selectedAccount.id == id) saveAuthenticated(selectedAccount, loginName, tailnetName)
    }

    fun snapshotJSON(): String = json.encodeToString(
        AccountSnapshot(accounts, selectedID, initialAccount.takeUnless { it.usesLegacyState })
    )

    /** UUID 标识本机节点；账号去重必须使用登录身份与 tailnet，不能使用显示名称。 */
    private fun identity(account: TailscaleAccount): Pair<String, String>? {
        val login = account.loginName.orEmpty().trim().lowercase()
        val tailnet = account.tailnetName.orEmpty().trim().trimEnd('.').lowercase()
        // 元数据尚未齐全时不猜测网络归属，以免合并同一用户的不同 tailnet。
        return if (login.isNotEmpty() && tailnet.isNotEmpty()) login to tailnet else null
    }

    private fun deduplicate(preferredID: String?) {
        val indices = mutableMapOf<Pair<String, String>, Int>()
        val unique = mutableListOf<TailscaleAccount>()
        accounts.forEach { account ->
            val key = identity(account)
            val index = key?.let(indices::get)
            if (index == null) {
                if (key != null) indices[key] = unique.size
                unique += account
            } else {
                val previous = unique[index]
                val preferred = if (account.id == preferredID) account else previous
                val other = if (account.id == preferredID) previous else account
                unique[index] = if (preferred.name.isBlank()) preferred.copy(name = other.name) else preferred
            }
        }
        accounts = unique
    }

    private fun validID(id: String): Boolean =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(id)
}
