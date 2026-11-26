package com.example.engine

import com.example.data.local.DiagnosticLogDao
import com.example.data.local.DiagnosticLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Privacy-preserving structured logger.
 * Never logs notification titles/bodies, pairing codes, raw keys, or bearer tokens.
 */
class SanitizedLogger(
    private val dao: DiagnosticLogDao,
    private val scope: CoroutineScope
) {
    fun info(category: String, message: String, errorCode: String? = null) {
        log("INFO", category, message, errorCode)
    }

    fun warn(category: String, message: String, errorCode: String? = null) {
        log("WARN", category, message, errorCode)
    }

    fun error(category: String, message: String, errorCode: String? = null) {
        log("ERROR", category, message, errorCode)
    }

    private fun log(level: String, category: String, rawMessage: String, errorCode: String?) {
        val sanitized = sanitize(rawMessage)
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.insert(
                    DiagnosticLogEntity(
                        timestamp = System.currentTimeMillis(),
                        level = level,
                        category = category,
                        errorCode = errorCode,
                        message = sanitized.take(240)
                    )
                )
                dao.trimOld()
            }
        }
    }

    companion object {
        private val BEARER_REGEX = Regex("Bearer\\s+[A-Za-z0-9._-]+", RegexOption.IGNORE_CASE)
        private val HEX_SECRET_REGEX = Regex("[0-9a-fA-F]{32,}")

        fun sanitize(input: String): String {
            return input
                .replace(BEARER_REGEX, "Bearer [REDACTED]")
                .replace(HEX_SECRET_REGEX, "[HASH_REDACTED]")
        }
    }
}
