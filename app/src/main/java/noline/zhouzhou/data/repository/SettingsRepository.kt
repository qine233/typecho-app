package noline.zhouzhou.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import noline.zhouzhou.data.model.CloudreveConfig
import noline.zhouzhou.data.model.SiteConfig
import noline.zhouzhou.data.remote.UrlForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "typecho_settings")

class SettingsRepository(private val context: Context) {

    private val secretStore = SecretStore(context)

    private object Keys {
        val SITE_URL = stringPreferencesKey("site_url")
        val XMLRPC_PATH = stringPreferencesKey("xmlrpc_path")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val BLOG_ID = stringPreferencesKey("blog_id")
        val BLOG_NAME = stringPreferencesKey("blog_name")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val IS_ADMIN = booleanPreferencesKey("is_admin")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val REMEMBER_ME = booleanPreferencesKey("remember_me")
        val NOLINE_KEY = stringPreferencesKey("noline_key_cipher")
        val NOLINE_URL_FORM = stringPreferencesKey("noline_url_form")
        val CLOUDREVE_SITE = stringPreferencesKey("cloudreve_site")
        val CLOUDREVE_EMAIL = stringPreferencesKey("cloudreve_email")
        val CLOUDREVE_PASSWORD = stringPreferencesKey("cloudreve_password_cipher")
    }

    val siteConfig: Flow<SiteConfig> = context.dataStore.data.map { prefs ->
        SiteConfig(
            siteUrl = prefs[Keys.SITE_URL] ?: "",
            xmlrpcPath = prefs[Keys.XMLRPC_PATH] ?: "/action/xmlrpc",
            username = prefs[Keys.USERNAME] ?: "",
            password = if (prefs[Keys.REMEMBER_ME] == true) prefs[Keys.PASSWORD] ?: "" else "",
            blogId = prefs[Keys.BLOG_ID] ?: "1"
        )
    }

    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IS_LOGGED_IN] ?: false
    }

    val blogName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOG_NAME] ?: ""
    }

    val isAdmin: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.IS_ADMIN] ?: false
    }

    val darkMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE] ?: "system"
    }

    val fontScale: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.FONT_SCALE] ?: 1.0f
    }

    val rememberMe: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.REMEMBER_ME] ?: true
    }

    suspend fun saveSiteConfig(config: SiteConfig, blogName: String = "", isAdmin: Boolean = false, rememberMe: Boolean = true) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SITE_URL] = config.siteUrl
            prefs[Keys.XMLRPC_PATH] = config.xmlrpcPath
            prefs[Keys.USERNAME] = config.username
            if (rememberMe) prefs[Keys.PASSWORD] = config.password
            else prefs.remove(Keys.PASSWORD)
            prefs[Keys.BLOG_ID] = config.blogId
            prefs[Keys.BLOG_NAME] = blogName
            prefs[Keys.IS_LOGGED_IN] = true
            prefs[Keys.IS_ADMIN] = isAdmin
            prefs[Keys.REMEMBER_ME] = rememberMe
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = false
            prefs[Keys.IS_ADMIN] = false
            prefs.remove(Keys.PASSWORD)
        }
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setRememberMe(remember: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_ME] = remember }
    }

    // ── NoLine 健康接口 ──

    /** 密钥只在内存里解密出来用，落盘的始终是 Keystore 密文。 */
    val nolineApiKey: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[Keys.NOLINE_KEY]?.let(secretStore::decrypt).orEmpty() }
        .flowOn(Dispatchers.IO)

    val nolineKeyConfigured: Flow<Boolean> = context.dataStore.data
        .map { !(it[Keys.NOLINE_KEY].isNullOrBlank()) }

    val nolineUrlForm: Flow<UrlForm> = context.dataStore.data.map { prefs ->
        UrlForm.entries.firstOrNull { it.name == prefs[Keys.NOLINE_URL_FORM] } ?: UrlForm.Rewritten
    }

    suspend fun setNolineApiKey(key: String) {
        val cipher = if (key.isBlank()) "" else secretStore.encryptOrThrow(key)
        context.dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(Keys.NOLINE_KEY) else prefs[Keys.NOLINE_KEY] = cipher
        }
    }

    suspend fun setNolineUrlForm(form: UrlForm) {
        context.dataStore.edit { it[Keys.NOLINE_URL_FORM] = form.name }
    }

    // ── Cloudreve 图床 ──

    val cloudreveConfig: Flow<CloudreveConfig> = context.dataStore.data
        .map { prefs ->
            CloudreveConfig(
                site = prefs[Keys.CLOUDREVE_SITE].orEmpty(),
                email = prefs[Keys.CLOUDREVE_EMAIL].orEmpty(),
                password = prefs[Keys.CLOUDREVE_PASSWORD]?.let(secretStore::decrypt).orEmpty()
            )
        }
        .flowOn(Dispatchers.IO)

    val cloudreveConfigured: Flow<Boolean> = context.dataStore.data
        .map { !(it[Keys.CLOUDREVE_SITE].isNullOrBlank()) && !(it[Keys.CLOUDREVE_PASSWORD].isNullOrBlank()) }

    /** 密码留空 = 沿用已存的，和 NoLine 密钥那一节一个规矩。 */
    suspend fun saveCloudreveConfig(site: String, email: String, password: String) {
        val normalized = normalizeSite(site)
        require(normalized.isNotBlank()) { "Cloudreve 地址不能为空" }
        require(email.isNotBlank()) { "要填 Cloudreve 的登录邮箱" }
        val stored = if (password.isBlank()) currentCloudrevePassword() else secretStore.encryptOrThrow(password)
        require(stored.isNotBlank()) { "要填 Cloudreve 的登录密码" }
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUDREVE_SITE] = normalized
            prefs[Keys.CLOUDREVE_EMAIL] = email.trim()
            prefs[Keys.CLOUDREVE_PASSWORD] = stored
        }
    }

    suspend fun clearCloudreveConfig() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.CLOUDREVE_SITE)
            prefs.remove(Keys.CLOUDREVE_EMAIL)
            prefs.remove(Keys.CLOUDREVE_PASSWORD)
        }
    }

    private suspend fun currentCloudrevePassword(): String =
        context.dataStore.data.map { it[Keys.CLOUDREVE_PASSWORD]?.let(secretStore::decrypt).orEmpty() }.first()

    /** 用户常把 https:// 省掉，OkHttp 拿到没协议的地址会直接抛。 */
    private fun normalizeSite(site: String): String {
        val trimmed = site.trim().trimEnd('/')
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }
}

/** 错误提示和日志里都不能出现完整密钥。 */
fun maskSecret(value: String): String =
    if (value.length <= 4) "已设置（${value.length} 位）"
    else "${value.take(4)}…（共 ${value.length} 位）"
