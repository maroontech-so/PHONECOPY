package com.example.core.crypto

import android.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9382 SPAKE2 over NIST P-256 (secp256r1).
 *
 * The 6-digit pairing code is NEVER used directly as an encryption key.
 * Instead, it feeds a PBKDF2-stretched scalar blinding ephemeral DH points with standard
 * generators M and N, producing a high-entropy shared secret Z, explicit bidirectional
 * key-confirmation MACs, and a 256-bit relationship root key K_root bound to both devices'
 * ECDSA identity public keys.
 */
object Spake2PairingEngine {

    private val curveSpec = ECNamedCurveTable.getParameterSpec("secp256r1")
    private val curve = curveSpec.curve
    private val G: ECPoint = curveSpec.g
    private val n: BigInteger = curveSpec.n
    private val h: BigInteger = curveSpec.h

    // RFC 9382 Section 4 standard P-256 generators M and N (compressed SEC1)
    private val M: ECPoint = curve.decodePoint(
        Hex.decode("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f")
    ).normalize()

    private val N: ECPoint = curve.decodePoint(
        Hex.decode("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49")
    ).normalize()

    private val random = SecureRandom()

    data class InitiatorState(
        val pairingSessionId: String,
        val primaryDeviceId: String,
        val scalarX: BigInteger,
        val scalarW: BigInteger,
        val wBytes: ByteArray,
        val messageABytes: ByteArray,
        val messageAB64: String
    )

    data class DerivedPairingKeys(
        val rootKey: ByteArray,
        val confirmKeyA: ByteArray,
        val confirmKeyB: ByteArray,
        val transcriptHash: ByteArray,
        val macA: String,
        val macB: String
    )

    fun generateSixDigitCode(): String {
        val code = random.nextInt(1_000_000)
        return "%06d".format(code)
    }

    fun startInitiator(
        pairingSessionId: String,
        primaryDeviceId: String,
        sixDigitCode: String
    ): InitiatorState {
        require(sixDigitCode.length == 6 && sixDigitCode.all { it.isDigit() }) {
            "Pairing code must be 6 digits"
        }
        val (w, wBytes) = derivePasswordScalar(sixDigitCode, pairingSessionId)
        val x = randomScalar()
        // pA = x*G + w*M
        val pA = G.multiply(x).add(M.multiply(w)).normalize()
        val msgABytes = pA.getEncoded(true)
        return InitiatorState(
            pairingSessionId = pairingSessionId,
            primaryDeviceId = primaryDeviceId,
            scalarX = x,
            scalarW = w,
            wBytes = wBytes,
            messageABytes = msgABytes,
            messageAB64 = Base64.encodeToString(msgABytes, Base64.NO_WRAP)
        )
    }

    /**
     * Secondary side: receives pA, validates point, computes pB = y*G + w*N, and derives keys.
     */
    fun completeResponder(
        pairingSessionId: String,
        primaryDeviceId: String,
        secondaryDeviceId: String,
        sixDigitCode: String,
        messageAB64: String
    ): Pair<String, DerivedPairingKeys> {
        require(sixDigitCode.length == 6 && sixDigitCode.all { it.isDigit() }) {
            "Pairing code must be 6 digits"
        }
        val pABytes = Base64.decode(messageAB64, Base64.NO_WRAP)
        val pA = decodeAndValidatePoint(pABytes)
        val (w, wBytes) = derivePasswordScalar(sixDigitCode, pairingSessionId)
        val y = randomScalar()

        // pB = y*G + w*N
        val pB = G.multiply(y).add(N.multiply(w)).normalize()
        val pBBytes = pB.getEncoded(true)

        // Z = h * y * (pA - w*M)
        val unblindedA = pA.subtract(M.multiply(w)).normalize()
        val z = unblindedA.multiply(h.multiply(y).mod(n)).normalize()
        require(!z.isInfinity) { "Invalid SPAKE2 shared secret" }

        val keys = deriveKeys(
            primaryDeviceId = primaryDeviceId,
            secondaryDeviceId = secondaryDeviceId,
            pABytes = pABytes,
            pBBytes = pBBytes,
            zBytes = z.getEncoded(true),
            wBytes = wBytes
        )
        return Base64.encodeToString(pBBytes, Base64.NO_WRAP) to keys
    }

    /**
     * Primary side: receives pB, validates point, computes Z = h * x * (pB - w*N), derives keys.
     */
    fun finishInitiator(
        state: InitiatorState,
        secondaryDeviceId: String,
        messageBB64: String
    ): DerivedPairingKeys {
        val pBBytes = Base64.decode(messageBB64, Base64.NO_WRAP)
        val pB = decodeAndValidatePoint(pBBytes)

        val unblindedB = pB.subtract(N.multiply(state.scalarW)).normalize()
        val z = unblindedB.multiply(h.multiply(state.scalarX).mod(n)).normalize()
        require(!z.isInfinity) { "Invalid SPAKE2 shared secret" }

        return deriveKeys(
            primaryDeviceId = state.primaryDeviceId,
            secondaryDeviceId = secondaryDeviceId,
            pABytes = state.messageABytes,
            pBBytes = pBBytes,
            zBytes = z.getEncoded(true),
            wBytes = state.wBytes
        )
    }

    fun constantTimeEqualsB64(aB64: String, bB64: String): Boolean {
        val a = Base64.decode(aB64, Base64.NO_WRAP)
        val b = Base64.decode(bB64, Base64.NO_WRAP)
        return MessageDigest.isEqual(a, b)
    }

    private fun derivePasswordScalar(code: String, sessionId: String): Pair<BigInteger, ByteArray> {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        val salt = ("PhoneBridge-SPAKE2-v1|$sessionId").toByteArray(Charsets.UTF_8)
        gen.init(code.toByteArray(Charsets.UTF_8), salt, 10_000)
        val okm = (gen.generateDerivedMacParameters(384) as KeyParameter).key
        val scalar = BigInteger(1, okm).mod(n - BigInteger.ONE).add(BigInteger.ONE)
        val wBytes = scalar.toByteArray()
        return scalar to wBytes
    }

    private fun randomScalar(): BigInteger {
        var k: BigInteger
        do {
            k = BigInteger(n.bitLength(), random)
        } while (k <= BigInteger.ONE || k >= n)
        return k
    }

    private fun decodeAndValidatePoint(encoded: ByteArray): ECPoint {
        val point = curve.decodePoint(encoded).normalize()
        require(!point.isInfinity && point.isValid) {
            "Invalid elliptic curve point"
        }
        return point
    }

    private fun deriveKeys(
        primaryDeviceId: String,
        secondaryDeviceId: String,
        pABytes: ByteArray,
        pBBytes: ByteArray,
        zBytes: ByteArray,
        wBytes: ByteArray
    ): DerivedPairingKeys {
        val transcript = buildTranscript(
            primaryDeviceId.toByteArray(Charsets.UTF_8),
            secondaryDeviceId.toByteArray(Charsets.UTF_8),
            pABytes,
            pBBytes,
            zBytes,
            wBytes
        )
        val ttHash = MessageDigest.getInstance("SHA-256").digest(transcript)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ttHash, "PhoneBridge-SPAKE2-Keys-v1".toByteArray(), ByteArray(0)))
        val okm = ByteArray(96)
        hkdf.generateBytes(okm, 0, 96)

        val rootKey = okm.copyOfRange(0, 32)
        val confirmKeyA = okm.copyOfRange(32, 64)
        val confirmKeyB = okm.copyOfRange(64, 96)

        val macA = Base64.encodeToString(hmacSha256(confirmKeyA, ttHash + byteArrayOf(0x01)), Base64.NO_WRAP)
        val macB = Base64.encodeToString(hmacSha256(confirmKeyB, ttHash + byteArrayOf(0x02)), Base64.NO_WRAP)

        return DerivedPairingKeys(
            rootKey = rootKey,
            confirmKeyA = confirmKeyA,
            confirmKeyB = confirmKeyB,
            transcriptHash = ttHash,
            macA = macA,
            macB = macB
        )
    }

    private fun buildTranscript(vararg parts: ByteArray): ByteArray {
        val totalSize = parts.sumOf { 4 + it.size }
        val buf = ByteBuffer.allocate(totalSize)
        for (part in parts) {
            buf.putInt(part.size)
            buf.put(part)
        }
        return buf.array()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
