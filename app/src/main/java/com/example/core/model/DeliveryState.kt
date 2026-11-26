package com.example.core.model

import kotlinx.serialization.Serializable

/**
 * Documented delivery state machine for PhoneBridge one-way event delivery.
 *
 * State semantics:
 * - CAPTURED: Captured locally on Primary and persisted in transactional outbox before any network I/O.
 * - PENDING: Enqueued and eligible for immediate transmission.
 * - SENDING: Claimed by an active transport worker (WebSocket or HTTPS) for delivery.
 * - RETRY_WAIT: Transient failure encountered; waiting until [nextAttemptAt] with exponential backoff + jitter.
 * - SERVER_ACCEPTED: Relay server durably stored the encrypted envelope. NOT proof of recipient delivery.
 * - DEVICE_RECEIVED: Secondary device authenticated, decrypted, validated, deduplicated, and durably persisted locally.
 * - READ: Secondary user opened or marked the event as read locally.
 * - EXPIRED: Event exceeded its retention deadline ([expiresAtEpochMs]) before completion.
 * - PERMANENT_FAILURE: Non-retryable error (e.g., revoked pairing, invalid payload, unauthorized device).
 */
@Serializable
enum class DeliveryState {
    CAPTURED,
    PENDING,
    SENDING,
    RETRY_WAIT,
    SERVER_ACCEPTED,
    DEVICE_RECEIVED,
    READ,
    EXPIRED,
    PERMANENT_FAILURE;

    val isTerminal: Boolean
        get() = this == READ || this == EXPIRED || this == PERMANENT_FAILURE
}

object DeliveryStateMachine {

    private val allowedTransitions: Map<DeliveryState, Set<DeliveryState>> = mapOf(
        DeliveryState.CAPTURED to setOf(
            DeliveryState.PENDING,
            DeliveryState.EXPIRED,
            DeliveryState.PERMANENT_FAILURE
        ),
        DeliveryState.PENDING to setOf(
            DeliveryState.SENDING,
            DeliveryState.EXPIRED,
            DeliveryState.PERMANENT_FAILURE
        ),
        DeliveryState.SENDING to setOf(
            DeliveryState.SERVER_ACCEPTED,
            DeliveryState.DEVICE_RECEIVED,
            DeliveryState.RETRY_WAIT,
            DeliveryState.PENDING,
            DeliveryState.EXPIRED,
            DeliveryState.PERMANENT_FAILURE
        ),
        DeliveryState.RETRY_WAIT to setOf(
            DeliveryState.SENDING,
            DeliveryState.PENDING,
            DeliveryState.EXPIRED,
            DeliveryState.PERMANENT_FAILURE
        ),
        DeliveryState.SERVER_ACCEPTED to setOf(
            DeliveryState.DEVICE_RECEIVED,
            DeliveryState.EXPIRED,
            DeliveryState.PERMANENT_FAILURE
        ),
        DeliveryState.DEVICE_RECEIVED to setOf(
            DeliveryState.READ,
            DeliveryState.EXPIRED
        ),
        DeliveryState.READ to emptySet(),
        DeliveryState.EXPIRED to emptySet(),
        DeliveryState.PERMANENT_FAILURE to emptySet()
    )

    fun canTransition(from: DeliveryState, to: DeliveryState): Boolean {
        if (from == to) return true
        return allowedTransitions[from]?.contains(to) == true
    }

    fun requireTransition(from: DeliveryState, to: DeliveryState) {
        require(canTransition(from, to)) {
            "Invalid PhoneBridge state transition: $from -> $to"
        }
    }
}
