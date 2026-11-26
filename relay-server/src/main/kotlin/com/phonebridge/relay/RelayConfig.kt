package com.phonebridge.relay

data class RelayConfig(
    val environment: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val rateLimitPerMinute: Int,
    val maxWsConnectionsPerDevice: Int,
    val credentialTtlDays: Long
) {
    companion object {
        fun fromEnv(): RelayConfig {
            val env = System.getenv("PHONEBRIDGE_ENV") ?: "development"
            val dbUrl = System.getenv("DATABASE_URL")
                ?: error("DATABASE_URL environment variable is required")
            val dbUser = System.getenv("DATABASE_USER")
                ?: if (env == "development") "phonebridge" else error("DATABASE_USER required")
            val dbPass = System.getenv("DATABASE_PASSWORD")
                ?: if (env == "development") "dev_only_change_me" else error("DATABASE_PASSWORD required")

            return RelayConfig(
                environment = env,
                port = (System.getenv("PORT") ?: "8443").toInt(),
                databaseUrl = dbUrl,
                databaseUser = dbUser,
                databasePassword = dbPass,
                rateLimitPerMinute = (System.getenv("RATE_LIMIT_PER_MINUTE") ?: "120").toInt(),
                maxWsConnectionsPerDevice = (System.getenv("MAX_WS_PER_DEVICE") ?: "2").toInt(),
                credentialTtlDays = (System.getenv("CREDENTIAL_TTL_DAYS") ?: "90").toLong()
            )
        }
    }
}
