package com.example.engine

import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.crypto.Spake2PairingEngine
import com.example.core.model.DeviceRole
import com.example.core.model.PairingCompleteRequest
import com.example.core.model.PairingInitiateRequest
import com.example.core.model.RelationshipStatus
import com.example.core.model.RevokeDeviceRequest
import com.example.data.local.LocalConfigStore
import com.example.data.local.RelationshipDao
import com.example.data.local.RelationshipEntity
import com.example.data.network.RelayResult
import com.example.data.network.RelayTransport
import java.security.SecureRandom

data class ActivePairingSession(
    val pairingSessionId: String,
    val sixDigitCode: String,
    val expiresAtEpochMs: Long,
    val primaryAuthToken: String,
    val initiatorState: Spake2PairingEngine.InitiatorState
)

sealed class PairingOutcome {
    data class Initiated(val session: ActivePairingSession) : PairingOutcome()
    data class Paired(val relationship: RelationshipEntity) : PairingOutcome()
    data class WaitingForPeer(val message: String) : PairingOutcome()
    data class Failed(val error: String) : PairingOutcome()
}

class PairingEngine(
    private val keystoreManager: KeystoreManager,
    private val relationshipDao: RelationshipDao,
    private val configStore: LocalConfigStore,
    private val transport: RelayTransport,
    private val logger: SanitizedLogger
) {
    private val random = SecureRandom()

    suspend fun initiatePrimaryPairing(): PairingOutcome {
        val config = configStore.getCurrent()
        val deviceId = keystoreManager.getDeviceId()
        val pubKey = keystoreManager.getIdentityPublicKeyB64()
        val sessionId = generateShortSessionCode()
        val pin = Spake2PairingEngine.generateSixDigitCode()

        val initiatorState = Spake2PairingEngine.startInitiator(
            pairingSessionId = sessionId,
            primaryDeviceId = deviceId,
            sixDigitCode = pin
        )

        val req = PairingInitiateRequest(
            deviceId = deviceId,
            deviceName = config.deviceName,
            identityPublicKeyB64 = pubKey,
            spake2MessageAB64 = initiatorState.messageAB64,
            pairingSessionId = sessionId
        )

        return when (val res = transport.initiatePairing(config.relayBaseUrl, req)) {
            is RelayResult.Success -> {
                logger.info("PAIRING", "Initiated SPAKE2 pairing session")
                PairingOutcome.Initiated(
                    ActivePairingSession(
                        pairingSessionId = res.data.pairingSessionId,
                        sixDigitCode = pin,
                        expiresAtEpochMs = res.data.expiresAtEpochMs,
                        primaryAuthToken = res.data.deviceToken,
                        initiatorState = initiatorState
                    )
                )
            }
            is RelayResult.Failure -> {
                logger.error("PAIRING", "Pairing initiation failed: ${res.error.summary}", res.error.code.name)
                PairingOutcome.Failed(res.error.summary)
            }
        }
    }

    suspend fun pollPrimaryCompletion(session: ActivePairingSession): PairingOutcome {
        val config = configStore.getCurrent()
        return when (val res = transport.pollPairingStatus(
            config.relayBaseUrl,
            session.pairingSessionId,
            session.primaryAuthToken
        )) {
            is RelayResult.Success -> {
                val status = res.data
                if (!status.completed ||
                    status.relationshipId == null ||
                    status.secondaryDeviceId == null ||
                    status.secondaryIdentityPublicKeyB64 == null ||
                    status.spake2MessageBB64 == null ||
                    status.keyConfirmationMacBB64 == null ||
                    status.identitySignatureBB64 == null
                ) {
                    return PairingOutcome.WaitingForPeer("Waiting for Secondary device")
                }

                val derived = runCatching {
                    Spake2PairingEngine.finishInitiator(
                        state = session.initiatorState,
                        secondaryDeviceId = status.secondaryDeviceId,
                        messageBB64 = status.spake2MessageBB64
                    )
                }.getOrElse {
                    return PairingOutcome.Failed("SPAKE2 key agreement failed")
                }

                // Verify key confirmation MAC & Secondary's ECDSA identity signature
                val macValid = Spake2PairingEngine.constantTimeEqualsB64(
                    derived.macB,
                    status.keyConfirmationMacBB64
                )
                val sigValid = keystoreManager.verify(
                    status.secondaryIdentityPublicKeyB64,
                    derived.transcriptHash,
                    status.identitySignatureBB64
                )

                if (!macValid || !sigValid) {
                    logger.error("CRYPTO", "Pairing key confirmation or identity signature mismatch", "UNAUTHORIZED_DEVICE")
                    return PairingOutcome.Failed("Cryptographic key confirmation failed (wrong PIN or MITM)")
                }

                val (wrappedKey, wrappedKeyIv) = keystoreManager.wrapSecret(derived.rootKey)
                val (wrappedTok, wrappedTokIv) = keystoreManager.wrapSecret(session.primaryAuthToken.toByteArray(Charsets.UTF_8))

                val entity = RelationshipEntity(
                    relationshipId = status.relationshipId,
                    role = DeviceRole.PRIMARY.name,
                    localDeviceId = keystoreManager.getDeviceId(),
                    peerDeviceId = status.secondaryDeviceId,
                    peerDeviceName = status.secondaryDeviceName ?: "Secondary Device",
                    peerIdentityPubB64 = status.secondaryIdentityPublicKeyB64,
                    wrappedRootKeyB64 = wrappedKey,
                    wrappedRootKeyIvB64 = wrappedKeyIv,
                    wrappedAuthTokenB64 = wrappedTok,
                    wrappedAuthTokenIvB64 = wrappedTokIv,
                    keyEpoch = 1,
                    nextSequenceNumber = 1L,
                    status = RelationshipStatus.ACTIVE.name,
                    createdAt = System.currentTimeMillis(),
                    revokedAt = null
                )
                relationshipDao.upsert(entity)
                logger.info("PAIRING", "Primary pairing verified and persisted")
                PairingOutcome.Paired(entity)
            }
            is RelayResult.Failure -> PairingOutcome.Failed(res.error.summary)
        }
    }

    /**
     * Secondary device pairing completion:
     * Fetches Primary's SPAKE2 message A via complete/join flow or pre-shared session lookup,
     * derives K_root, signs transcript with hardware Keystore ECDSA identity key, and stores
     * wrapped K_root locally.
     */
    suspend fun completeSecondaryPairing(
        pairingSessionId: String,
        sixDigitCode: String,
        primaryDeviceId: String = "",
        primarySpake2MsgAB64: String = ""
    ): PairingOutcome {
        val config = configStore.getCurrent()
        val secondaryId = keystoreManager.getDeviceId()
        val secondaryPub = keystoreManager.getIdentityPublicKeyB64()
        val cleanSessionId = pairingSessionId.trim()

        var resolvedPrimaryId = primaryDeviceId.trim()
        var resolvedSpakeA = primarySpake2MsgAB64.trim()
        if (resolvedPrimaryId.isBlank() || resolvedSpakeA.isBlank()) {
            when (val lookup = transport.lookupPairingSession(config.relayBaseUrl, cleanSessionId)) {
                is RelayResult.Success -> {
                    resolvedPrimaryId = lookup.data.primaryDeviceId
                    resolvedSpakeA = lookup.data.spake2MessageAB64
                }
                is RelayResult.Failure -> {
                    return PairingOutcome.Failed(
                        "Session lookup failed (${lookup.error.summary}). Paste invite or expand manual keys."
                    )
                }
            }
        }

        val (msgBB64, derived) = runCatching {
            Spake2PairingEngine.completeResponder(
                pairingSessionId = cleanSessionId,
                primaryDeviceId = resolvedPrimaryId,
                secondaryDeviceId = secondaryId,
                sixDigitCode = sixDigitCode.trim(),
                messageAB64 = resolvedSpakeA
            )
        }.getOrElse {
            return PairingOutcome.Failed("Invalid pairing inputs or curve point")
        }

        val sigB = keystoreManager.sign(derived.transcriptHash)
        val req = PairingCompleteRequest(
            pairingSessionId = pairingSessionId.trim(),
            secondaryDeviceId = secondaryId,
            secondaryDeviceName = config.deviceName,
            secondaryIdentityPublicKeyB64 = secondaryPub,
            spake2MessageBB64 = msgBB64,
            keyConfirmationMacBB64 = derived.macB,
            identitySignatureBB64 = sigB
        )

        return when (val res = transport.completePairing(config.relayBaseUrl, req)) {
            is RelayResult.Success -> {
                val resp = res.data
                val (wrappedKey, wrappedKeyIv) = keystoreManager.wrapSecret(derived.rootKey)
                val (wrappedTok, wrappedTokIv) = keystoreManager.wrapSecret(resp.secondaryToken.toByteArray(Charsets.UTF_8))

                val entity = RelationshipEntity(
                    relationshipId = resp.relationshipId,
                    role = DeviceRole.SECONDARY.name,
                    localDeviceId = secondaryId,
                    peerDeviceId = resp.primaryDeviceId,
                    peerDeviceName = resp.primaryDeviceName,
                    peerIdentityPubB64 = resp.primaryIdentityPublicKeyB64,
                    wrappedRootKeyB64 = wrappedKey,
                    wrappedRootKeyIvB64 = wrappedKeyIv,
                    wrappedAuthTokenB64 = wrappedTok,
                    wrappedAuthTokenIvB64 = wrappedTokIv,
                    keyEpoch = resp.keyEpoch,
                    nextSequenceNumber = 1L,
                    status = RelationshipStatus.ACTIVE.name,
                    createdAt = System.currentTimeMillis(),
                    revokedAt = null
                )
                relationshipDao.upsert(entity)
                logger.info("PAIRING", "Secondary pairing completed and key bound")
                PairingOutcome.Paired(entity)
            }
            is RelayResult.Failure -> {
                logger.error("PAIRING", "Secondary pairing failed: ${res.error.summary}", res.error.code.name)
                PairingOutcome.Failed(res.error.summary)
            }
        }
    }

    /**
     * Rotates the relationship encryption epoch locally using HKDF-SHA256 forward ratcheting.
     */
    suspend fun rotateEncryptionKey(): Int? {
        val rel = relationshipDao.getActiveRelationship() ?: return null
        val oldRoot = keystoreManager.unwrapSecret(rel.wrappedRootKeyB64, rel.wrappedRootKeyIvB64)
        val newEpoch = rel.keyEpoch + 1
        val newRoot = EnvelopeCryptoEngine.deriveEventEncryptionKey(oldRoot, rel.relationshipId, newEpoch)
        val (wrapped, iv) = keystoreManager.wrapSecret(newRoot)
        relationshipDao.updateRotatedKey(rel.relationshipId, wrapped, iv, newEpoch)
        logger.info("CRYPTO", "Rotated encryption key to epoch=$newEpoch")
        return newEpoch
    }

    suspend fun revokeActivePairing(reason: String = "User initiated revocation"): Boolean {
        val rel = relationshipDao.getActiveRelationship() ?: return false
        val config = configStore.getCurrent()
        val authToken = keystoreManager.unwrapSecret(rel.wrappedAuthTokenB64, rel.wrappedAuthTokenIvB64)
            .toString(Charsets.UTF_8)

        transport.revokeDevice(
            config.relayBaseUrl,
            authToken,
            RevokeDeviceRequest(
                relationshipId = rel.relationshipId,
                targetDeviceId = rel.peerDeviceId,
                reason = reason
            )
        )
        relationshipDao.markRevoked(rel.relationshipId, System.currentTimeMillis())
        logger.warn("PAIRING", "Pairing relationship revoked locally and on relay", "REVOKED_PAIRING")
        return true
    }

    private fun generateShortSessionCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString {
            append("PB-")
            repeat(6) { append(chars[random.nextInt(chars.length)]) }
        }
    }
}
