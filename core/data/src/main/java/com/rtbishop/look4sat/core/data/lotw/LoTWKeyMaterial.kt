package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.repository.LoTWCertificate
import com.rtbishop.look4sat.core.domain.repository.LoTWOperationException
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.BadPaddingException

internal data class LoTWKeyMaterial(val key: PrivateKey, val certificate: X509Certificate, val info: LoTWCertificate) {
    companion object {
        fun read(bytes: ByteArray, password: CharArray, now: Long): LoTWKeyMaterial {
            if (bytes.isEmpty() || bytes.size > MAX_CERTIFICATE_BYTES) fail(LoTWProblem.CERTIFICATE_INVALID)
            val key: PrivateKey
            val cert: X509Certificate
            val store = try {
                KeyStore.getInstance("PKCS12").apply { bytes.inputStream().use { load(it, password) } }
            } catch (_: Exception) {
                // Modern TQSL / OpenSSL 3 exports use PBES2+AES-CBC which Android's legacy
                // Bouncy Castle parser cannot read. Fall back to our own PBES2 reader; if
                // that fails too, report the real reason (format vs password).
                if (isPbes2(bytes)) {
                    try {
                        val parsed = Pkcs12Reader.read(bytes, password)
                        parsed.first to parsed.second
                    } catch (_: BadPaddingException) {
                        fail(LoTWProblem.CERTIFICATE_PASSWORD)
                    } catch (_: Exception) {
                        fail(LoTWProblem.CERTIFICATE_FORMAT)
                    }
                } else {
                    fail(LoTWProblem.CERTIFICATE_PASSWORD)
                }
            }
            if (store is Pair<*, *>) {
                @Suppress("UNCHECKED_CAST")
                key = store.first as PrivateKey
                @Suppress("UNCHECKED_CAST")
                cert = store.second as X509Certificate
            } else {
                val ks = store as KeyStore
                val aliases = Collections.list(ks.aliases()).filter { ks.isKeyEntry(it) }
                if (aliases.size != 1) fail(LoTWProblem.CERTIFICATE_INVALID)
                val alias = aliases.single()
                key = try { ks.getKey(alias, password) as? PrivateKey }
                catch (_: Exception) { fail(LoTWProblem.CERTIFICATE_PASSWORD) }
                    ?: fail(LoTWProblem.CERTIFICATE_INVALID)
                cert = ks.getCertificate(alias) as? X509Certificate ?: fail(LoTWProblem.CERTIFICATE_INVALID)
            }
            if (key.algorithm != "RSA" || cert.publicKey.algorithm != "RSA") fail(LoTWProblem.CERTIFICATE_INVALID)
            try { cert.checkValidity(Date(now)) } catch (_: Exception) { fail(LoTWProblem.CERTIFICATE_EXPIRED) }
            val info = try { metadata(cert) } catch (_: Exception) { fail(LoTWProblem.CERTIFICATE_INVALID) }
            // Use the platform's crypto implementation, including PKCS#1 padding. No custom cryptography.
            val challenge = "Look4Sat LoTW certificate key check".toByteArray(Charsets.US_ASCII)
            val signed = Signature.getInstance("SHA1withRSA").run { initSign(key); update(challenge); sign() }
            val valid = Signature.getInstance("SHA1withRSA").run { initVerify(cert); update(challenge); verify(signed) }
            if (!valid) fail(LoTWProblem.CERTIFICATE_INVALID)
            return LoTWKeyMaterial(key, cert, info)
        }

        /** True when the PKCS12 uses PBES2 (OID 1.2.840.113549.1.5.13), the default
         *  algorithm of OpenSSL 3 / modern TQSL. Android's legacy BC parser can't read it. */
        private fun isPbes2(bytes: ByteArray): Boolean {
            val oid = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0d)
            if (bytes.size < oid.size) return false
            outer@ for (i in 0..bytes.size - oid.size) {
                for (j in oid.indices) if (bytes[i + j] != oid[j]) continue@outer
                return true
            }
            return false
        }

        private fun metadata(cert: X509Certificate): LoTWCertificate {
            val oid = byteArrayOf(0x2b, 0x06, 0x01, 0x04, 0x01, 0xe0.toByte(), 0x3c, 0x01, 0x01)
            val subject = DerValue.read(cert.subjectX500Principal.encoded).single()
            require(subject.tag == 0x30)
            val calls = subject.children().flatMap { it.children() }.mapNotNull { attribute ->
                val pair = attribute.children()
                if (pair.size == 2 && pair[0].tag == 6 && pair[0].data.contentEquals(oid)) pair[1].text() else null
            }
            val call = calls.single().trim().uppercase(Locale.US)
            require(call.matches(Regex("[A-Z0-9]+(/[A-Z0-9]+)*")) && call.any(Char::isDigit) && call.any(Char::isLetter))
            val first = extension(cert, "2")
            val last = extension(cert, "3").takeUnless { it == "0000-00-00" }.orEmpty()
            require(validDate(first) && (last.isBlank() || validDate(last)) && (last.isBlank() || last >= first))
            val dxcc = extension(cert, "4").toInt()
            require(dxcc > 0)
            return LoTWCertificate(call, dxcc, cert.serialNumber.toString(16), utc(cert.notAfter.time, "yyyy-MM-dd"), first, last)
        }

        private fun extension(cert: X509Certificate, suffix: String): String {
            val bytes = cert.getExtensionValue("1.3.6.1.4.1.12348.1.$suffix") ?: return ""
            val outer = DerValue.read(bytes).single()
            require(outer.tag == 4)
            // TrustedQSL uses raw ASCII extension values. Also accept DER string-wrapped values.
            return if (outer.data.all { it.toInt() in 32..126 || it.toInt() == 0 }) {
                outer.data.toString(Charsets.US_ASCII).trim('\u0000', ' ')
            } else DerValue.read(outer.data).single().text().trim()
        }
    }
}

internal const val MAX_CERTIFICATE_BYTES = 1024 * 1024
internal fun fail(problem: LoTWProblem, detail: String = ""): Nothing = throw LoTWOperationException(problem, detail)
internal fun utc(millis: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))
internal fun validDate(value: String): Boolean = value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value) != null
}.getOrDefault(false)

/** Only bounded DER metadata decoding; cryptographic verification is delegated to JCA. */
internal data class DerValue(val tag: Int, val data: ByteArray) {
    fun children(): List<DerValue> = read(data)
    fun text(): String = when (tag) {
        12, 19, 20, 22, 23, 24 -> data.toString(Charsets.UTF_8)
        30 -> data.toString(Charsets.UTF_16BE)
        else -> error("Not an ASN.1 string")
    }
    companion object {
        fun read(bytes: ByteArray): List<DerValue> {
            val result = mutableListOf<DerValue>()
            var offset = 0
            while (offset < bytes.size) {
                require(bytes.size - offset >= 2)
                val tag = bytes[offset++].toInt() and 255
                var length = bytes[offset++].toInt() and 255
                if (length and 128 != 0) {
                    val size = length and 127
                    require(size in 1..3 && size <= bytes.size - offset)
                    length = 0
                    repeat(size) { length = (length shl 8) or (bytes[offset++].toInt() and 255) }
                }
                require(length <= bytes.size - offset)
                result += DerValue(tag, bytes.copyOfRange(offset, offset + length))
                offset += length
            }
            return result
        }
    }
}
