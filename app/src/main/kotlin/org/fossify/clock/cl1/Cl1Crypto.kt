package org.fossify.clock.cl1

import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Cl1EncryptedEmail(
    val slot: Cl1Bytes,
    val ciphertext: Cl1Bytes,
    val tag: Cl1Bytes,
) {
    fun toSourceRecord(): Cl1SourceRecord {
        return Cl1SourceRecord(slot, ciphertext, tag)
    }
}

object Cl1Crypto {
    private val secureRandom = SecureRandom()

    fun generateSecret(): Cl1Bytes {
        return Cl1Bytes.copyOf(ByteArray(Cl1Limits.SECRET_BYTES).also(secureRandom::nextBytes))
    }

    fun generateCreateToken(): Cl1Bytes {
        return Cl1Bytes.copyOf(ByteArray(CREATE_TOKEN_BYTES).also(secureRandom::nextBytes))
    }

    fun deriveSlot(secret: Cl1Bytes): Cl1Bytes {
        require(secret.size == Cl1Limits.SECRET_BYTES)
        return Cl1Bytes.copyOf(hkdf(secret.toByteArray(), SLOT_INFO, Cl1Limits.SLOT_BYTES))
    }

    fun encryptEmail(
        secret: Cl1Bytes,
        email: Cl1CanonicalEmail,
    ): Cl1EncryptedEmail {
        require(secret.size == Cl1Limits.SECRET_BYTES)
        val slot = deriveSlot(secret)
        val key = hkdf(secret.toByteArray(), EMAIL_KEY_INFO, AES_KEY_BYTES)
        val nonce = hkdf(secret.toByteArray(), EMAIL_NONCE_INFO, GCM_NONCE_BYTES)
        val aad = EMAIL_AAD.toByteArray(StandardCharsets.UTF_8) + slot.toByteArray()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(Cl1Email.preEncode(email))
        val ciphertextSize = encrypted.size - Cl1Limits.GCM_TAG_BYTES
        return Cl1EncryptedEmail(
            slot = slot,
            ciphertext = Cl1Bytes.copyOf(encrypted.copyOfRange(0, ciphertextSize)),
            tag = Cl1Bytes.copyOf(encrypted.copyOfRange(ciphertextSize, encrypted.size))
        )
    }

    fun decryptEmail(
        secret: Cl1Bytes,
        record: Cl1SourceRecord,
        domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    ): Cl1CanonicalEmail {
        require(secret.size == Cl1Limits.SECRET_BYTES)
        val derivedSlot = deriveSlot(secret)
        if (!constantTimeEquals(derivedSlot, record.slot)) {
            throw Cl1CryptoException("Slot does not match the mirror secret")
        }
        val key = hkdf(secret.toByteArray(), EMAIL_KEY_INFO, AES_KEY_BYTES)
        val nonce = hkdf(secret.toByteArray(), EMAIL_NONCE_INFO, GCM_NONCE_BYTES)
        val aad = EMAIL_AAD.toByteArray(StandardCharsets.UTF_8) + record.slot.toByteArray()
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        val encoded = try {
            cipher.doFinal(record.emailCiphertext.toByteArray() + record.gcmTag.toByteArray())
        } catch (_: GeneralSecurityException) {
            throw Cl1CryptoException("Email authentication failed")
        }
        return try {
            Cl1Email.decode(encoded, domainToAscii)
        } catch (_: Cl1EmailException) {
            throw Cl1CryptoException("Decrypted email is invalid")
        }
    }

    fun revisionKey(secret: Cl1Bytes): ByteArray {
        require(secret.size == Cl1Limits.SECRET_BYTES)
        return hkdf(secret.toByteArray(), REVISION_KEY_INFO, SHA256_BYTES)
    }

    fun revision(
        secret: Cl1Bytes,
        canonicalEvent: ByteArray,
    ): Cl1Bytes {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(revisionKey(secret), HMAC_SHA256))
        return Cl1Bytes.copyOf(mac.doFinal(canonicalEvent).copyOf(Cl1Limits.REVISION_BYTES))
    }

    fun constantTimeEquals(left: Cl1Bytes, right: Cl1Bytes): Boolean {
        return MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8)
        )
    }

    internal fun hkdf(
        inputKey: ByteArray,
        info: String,
        length: Int,
    ): ByteArray {
        require(length in 1..HKDF_MAX_BYTES)
        val extract = Mac.getInstance(HMAC_SHA256)
        extract.init(SecretKeySpec(ByteArray(SHA256_BYTES), HMAC_SHA256))
        val pseudoRandomKey = extract.doFinal(inputKey)

        val result = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            val expand = Mac.getInstance(HMAC_SHA256)
            expand.init(SecretKeySpec(pseudoRandomKey, HMAC_SHA256))
            expand.update(previous)
            expand.update(info.toByteArray(StandardCharsets.UTF_8))
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val copySize = minOf(previous.size, length - written)
            previous.copyInto(result, written, 0, copySize)
            written += copySize
            counter++
        }
        return result
    }

    private const val CREATE_TOKEN_BYTES = 16
    private const val AES_KEY_BYTES = 16
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val SHA256_BYTES = 32
    private const val HKDF_MAX_BYTES = 255 * SHA256_BYTES
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val SLOT_INFO = "CL1/slot"
    private const val EMAIL_KEY_INFO = "CL1/email-key"
    private const val EMAIL_NONCE_INFO = "CL1/email-nonce"
    private const val REVISION_KEY_INFO = "CL1/revision-key"
    private const val EMAIL_AAD = "CL1/source/email"
}

class Cl1CryptoException(
    message: String,
) : IllegalArgumentException(message)
