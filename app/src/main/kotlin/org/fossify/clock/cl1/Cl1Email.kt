@file:Suppress("MagicNumber")

package org.fossify.clock.cl1

import java.net.IDN
import java.nio.charset.StandardCharsets
import java.util.Locale

data class Cl1CanonicalEmail(
    val value: String,
) {
    init {
        require(value.isNotEmpty())
    }
}

fun interface Cl1DomainToAscii {
    fun convert(value: String): String
}

object Cl1JdkDomainToAscii : Cl1DomainToAscii {
    override fun convert(value: String): String {
        return IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES)
    }
}

object Cl1Email {
    fun canonicalize(
        email: String,
        domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    ): Cl1CanonicalEmail {
        val normalized = try {
            Cl1Text.normalize(email)
        } catch (_: Cl1IncompatibleException) {
            throw Cl1EmailException("Email is not valid Unicode")
        }
        if (normalized.codePoints().anyMatch(::isForbiddenCodePoint)) {
            throw Cl1EmailException("Email contains whitespace or control characters")
        }
        val separator = normalized.indexOf('@')
        if (
            separator <= 0 ||
            separator != normalized.lastIndexOf('@') ||
            separator == normalized.lastIndex
        ) {
            throw Cl1EmailException("Email must contain exactly one separator")
        }

        val localPart = normalized.substring(0, separator)
        val localBytes = localPart.toByteArray(StandardCharsets.UTF_8)
        if (localBytes.size !in 1..MAX_LOCAL_BYTES) {
            throw Cl1EmailException("Invalid local part length")
        }

        val asciiDomain = try {
            domainToAscii.convert(normalized.substring(separator + 1))
                .lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            throw Cl1EmailException("Invalid IDNA domain")
        }
        validateAsciiDomain(asciiDomain)

        val canonical = "$localPart@$asciiDomain"
        if (canonical.toByteArray(StandardCharsets.UTF_8).size > MAX_EMAIL_BYTES) {
            throw Cl1EmailException("Canonical email is too long")
        }
        return Cl1CanonicalEmail(canonical)
    }

    fun preEncode(email: Cl1CanonicalEmail): ByteArray {
        val separator = email.value.lastIndexOf('@')
        val localPart = email.value.substring(0, separator)
        val domain = email.value.substring(separator + 1)
        val prefix = when (domain) {
            GMAIL_DOMAIN -> GMAIL_PREFIX
            GOOGLEMAIL_DOMAIN -> GOOGLEMAIL_PREFIX
            else -> FULL_EMAIL_PREFIX
        }
        val text = if (prefix == FULL_EMAIL_PREFIX) email.value else localPart
        return byteArrayOf(prefix) + text.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(
        encoded: ByteArray,
        domainToAscii: Cl1DomainToAscii = Cl1JdkDomainToAscii,
    ): Cl1CanonicalEmail {
        if (encoded.size < MIN_ENCODED_BYTES) {
            throw Cl1EmailException("Encoded email is empty")
        }
        val suffix = when (encoded.first()) {
            FULL_EMAIL_PREFIX -> ""
            GMAIL_PREFIX -> "@$GMAIL_DOMAIN"
            GOOGLEMAIL_PREFIX -> "@$GOOGLEMAIL_DOMAIN"
            else -> throw Cl1EmailException("Unknown encoded email prefix")
        }
        val decoded = try {
            Cl1Text.decodeNfc(encoded.copyOfRange(1, encoded.size))
        } catch (_: Cl1FormatException) {
            throw Cl1EmailException("Encoded email is not valid NFC UTF-8")
        }
        if (decoded.isEmpty()) {
            throw Cl1EmailException("Encoded email body is empty")
        }
        return canonicalize(decoded + suffix, domainToAscii)
    }

    private fun validateAsciiDomain(value: String) {
        if (value.isEmpty() || value.length > MAX_DOMAIN_BYTES || !value.isAscii()) {
            throw Cl1EmailException("Invalid ASCII domain")
        }
        val labels = value.split('.')
        if (labels.any { it.isEmpty() || it.length > MAX_LABEL_BYTES }) {
            throw Cl1EmailException("Invalid domain label")
        }
    }

    private fun isForbiddenCodePoint(codePoint: Int): Boolean {
        return codePoint in 0x0000..0x001f ||
            codePoint in 0x007f..0x009f ||
            codePoint in 0x0009..0x000d ||
            codePoint == 0x0020 ||
            codePoint == 0x0085 ||
            codePoint == 0x00a0 ||
            codePoint == 0x1680 ||
            codePoint in 0x2000..0x200a ||
            codePoint == 0x2028 ||
            codePoint == 0x2029 ||
            codePoint == 0x202f ||
            codePoint == 0x205f ||
            codePoint == 0x3000
    }

    private fun String.isAscii(): Boolean = all { it.code <= ASCII_MAX }

    private const val MAX_LOCAL_BYTES = 64
    private const val MAX_DOMAIN_BYTES = 253
    private const val MAX_LABEL_BYTES = 63
    private const val MAX_EMAIL_BYTES = 254
    private const val MIN_ENCODED_BYTES = 2
    private const val ASCII_MAX = 0x7f
    private const val FULL_EMAIL_PREFIX: Byte = 0x00
    private const val GMAIL_PREFIX: Byte = 0x01
    private const val GOOGLEMAIL_PREFIX: Byte = 0x02
    private const val GMAIL_DOMAIN = "gmail.com"
    private const val GOOGLEMAIL_DOMAIN = "googlemail.com"
}

class Cl1EmailException(
    message: String,
) : IllegalArgumentException(message)
