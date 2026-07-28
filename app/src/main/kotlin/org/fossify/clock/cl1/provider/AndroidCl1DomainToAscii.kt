package org.fossify.clock.cl1.provider

import android.icu.text.IDNA
import org.fossify.clock.cl1.Cl1DomainToAscii

object AndroidCl1DomainToAscii : Cl1DomainToAscii {
    private val idna = IDNA.getUTS46Instance(
        IDNA.USE_STD3_RULES or
            IDNA.CHECK_BIDI or
            IDNA.CHECK_CONTEXTJ or
            IDNA.CHECK_CONTEXTO or
            IDNA.NONTRANSITIONAL_TO_ASCII
    )

    override fun convert(value: String): String {
        val result = StringBuilder()
        val info = IDNA.Info()
        idna.nameToASCII(value, result, info)
        if (info.hasErrors()) {
            throw IllegalArgumentException("Invalid IDNA2008 domain: ${info.errors}")
        }
        return result.toString()
    }
}
