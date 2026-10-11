package dev.aigw.core.provider.antigravity

/** Runtime configuration; never ship an upstream application's OAuth credentials. */
class AntigravityOAuthCredentials(val clientId: String, val clientSecret: String) {
    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun requireConfigured(): AntigravityOAuthCredentials {
        check(isConfigured) { "请先在“供应商与账号 → 更多操作 → Antigravity OAuth 配置”中填写自己的 OAuth Client ID 和 Client Secret。" }
        return this
    }

    // Avoid exposing secrets if a caller logs the configuration object.
    override fun toString(): String = "AntigravityOAuthCredentials(configured=$isConfigured)"

    companion object {
        const val CLIENT_ID_OPTION = "oauthClientId"
        const val CLIENT_SECRET_OPTION = "oauthClientSecret"
        const val CLIENT_ID_ENV = "OCEAN_ANTIGRAVITY_CLIENT_ID"
        const val CLIENT_SECRET_ENV = "OCEAN_ANTIGRAVITY_CLIENT_SECRET"

        fun fromOptions(options: Map<String, String>, environment: (String) -> String? = System::getenv): AntigravityOAuthCredentials {
            fun value(option: String, variable: String): String =
                options[option]?.trim()?.takeIf { it.isNotEmpty() } ?: environment(variable)?.trim().orEmpty()
            return AntigravityOAuthCredentials(value(CLIENT_ID_OPTION, CLIENT_ID_ENV), value(CLIENT_SECRET_OPTION, CLIENT_SECRET_ENV))
        }
    }
}
