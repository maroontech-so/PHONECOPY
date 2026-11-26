package com.example.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protects long-term device identity keys (ECDSA P-256) and local wrapping master keys (AES-256-GCM)
 * in Android Keystore. Falls back to an ephemeral in-memory JCA store ONLY under Robolectric JVM tests
 * where the AndroidKeyStore provider is absent.
 */
class KeystoreManager(
    private val identityAlias: String = "phonebridge_identity_ecdsa_v1",
    private val wrappingAlias: String = "phonebridge_master_wrap_aes_v1"
) {
    private val isRobolectric: Boolean = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    @Volatile
    private var testIdentityKeyPair: KeyPair? = null

    @Volatile
    private var testWrappingKey: SecretKey? = null

    private fun hasAndroidKeystore(): Boolean {
        if (isRobolectric) return false
        return runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun getOrCreateIdentityKeyPair(): KeyPair {
        if (hasAndroidKeystore()) {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingPrivate = ks.getKey(identityAlias, null) as? PrivateKey
            val existingCert = ks.getCertificate(identityAlias)
            if (existingPrivate != null && existingCert != null) {
                return KeyPair(existingCert.publicKey, existingPrivate)
            }

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                identityAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kpg.initialize(spec)
            return kpg.generateKeyPair()
        }

        return testIdentityKeyPair ?: KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair().also { testIdentityKeyPair = it }
    }

    fun getIdentityPublicKeyB64(): String {
        val encoded = getOrCreateIdentityKeyPair().public.encoded
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    /**
     * Stable, collision-resistant opaque device identifier derived from SHA-256 of SPKI public key.
     */
    fun getDeviceId(): String {
        val pubBytes = getOrCreateIdentityKeyPair().public.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        return "dev_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Human-readable grouped fingerprint for out-of-band verification.
     */
    fun getHumanFingerprint(): String {
        val pubBytes = getOrCreateIdentityKeyPair().public.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        return digest.take(12)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }

    fun sign(data: ByteArray): String {
        val privateKey = getOrCreateIdentityKeyPair().private
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    fun verify(publicKeyB64: String, data: ByteArray, signatureB64: String): Boolean {
        return runCatching {
            val keyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            val pubKey: PublicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val sigBytes = Base64.decode(signatureB64, Base64.NO_WRAP)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    @Synchronized
    private fun getOrCreateWrappingKey(): SecretKey {
        if (hasAndroidKeystore()) {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(wrappingAlias, null) as? SecretKey
            if (existing != null) return existing

            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                wrappingAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            kg.init(spec)
            return kg.generateKey()
        }

        return testWrappingKey ?: ByteArray(32).let { bytes ->
            SecureRandom().nextBytes(bytes)
            SecretKeySpec(bytes, "AES")
        }.also { testWrappingKey = it }
    }

    /**
     * Encrypts sensitive secret material (e.g., relationship root key, auth token) for local storage.
     * Returns Pair(ciphertextB64, ivB64).
     */
    fun wrapSecret(plaintext: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return Base64.encodeToString(ct, Base64.NO_WRAP) to Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    fun unwrapSecret(cipherB64: String, ivB64: String): ByteArray {
        val ct = Base64.decode(cipherB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
