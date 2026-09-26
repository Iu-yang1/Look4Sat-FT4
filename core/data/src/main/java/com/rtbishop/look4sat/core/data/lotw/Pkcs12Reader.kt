/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.rtbishop.look4sat.core.data.lotw

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal PKCS#12 reader for the PBES2/AES-CBC format that OpenSSL 3 and modern
 * TQSL produce by default. Android's legacy bundled Bouncy Castle PKCS12 parser
 * cannot handle PBES2, so we parse the DER structure ourselves and decrypt with
 * the platform JCE (PBKDF2WithHmacSHA1/256 + AES/CBC), which ships on Android.
 *
 * Handles both layouts:
 *  - OpenSSL `-certpbe NONE`: plaintext certificate bags + a PBES2-shrouded key
 *    inside a plaintext `data` ContentInfo.
 *  - Modern TQSL: the certificate SafeContents wrapped in an `encryptedData`
 *    ContentInfo (PBES2), plus the PBES2-shrouded key in a plaintext `data`
 *    ContentInfo.
 */
internal object Pkcs12Reader {

    private val OID_DATA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x01)
    private val OID_ENCRYPTED_DATA = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x07, 0x06)
    private val OID_PKCS8_SHROUDED_KEY_BAG = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x0c, 0x0a, 0x01, 0x02)
    private val OID_CERT_BAG = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x0c, 0x0a, 0x01, 0x03)
    private val OID_X509_CERT = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x09, 0x16, 0x01)
    private val OID_PBES2 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0d)
    private val OID_PBKDF2 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x05, 0x0c)
    private val OID_HMAC_SHA1 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x07)
    private val OID_HMAC_SHA256 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x09)
    private val OID_HMAC_SHA384 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x0a)
    private val OID_HMAC_SHA512 = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x02, 0x0b)
    private val OID_AES_256_CBC = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2a)
    private val OID_AES_192_CBC = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x16)
    private val OID_AES_128_CBC = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x02)
    private val OID_DES_EDE3_CBC = byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x03, 0x07)

    fun read(bytes: ByteArray, password: CharArray): Pair<PrivateKey, X509Certificate> {
        val pfx = DerReader.read(bytes)
        require(pfx.tag == 0x30) { "not a PFX" }
        val pfxChildren = DerReader.children(pfx.content)
        require(pfxChildren.size >= 2) { "PFX too small" }
        // authSafe ContentInfo
        val authSafeCi = DerReader.children(pfxChildren[1].content)
        require(authSafeCi.size >= 2) { "authSafe malformed" }
        // content: [0] EXPLICIT OCTET STRING -> AuthenticatedSafe
        val explicit = DerReader.children(authSafeCi[1].content).first()
        val octet = DerReader.read(explicit.content)
        // octet.content is the AuthenticatedSafe body: a sequence of ContentInfo.
        var privateKey: PrivateKey? = null
        val certs = mutableListOf<X509Certificate>()
        for (info in DerReader.children(octet.content)) {
            val parts = DerReader.children(info.content)
            if (parts.isEmpty()) continue
            when {
                parts[0].content.contentEquals(OID_DATA) ->
                    parseSafeContentsContent(parts, password, certs) { privateKey = it }
                parts[0].content.contentEquals(OID_ENCRYPTED_DATA) ->
                    parseEncryptedData(parts, password, certs)
            }
        }
        val key = privateKey ?: error("no private key bag found")
        require(certs.isNotEmpty()) { "no certificate bag found" }
        return key to certs[0]
    }

    /** A plaintext ContentInfo: [0] EXPLICIT OCTET STRING -> full SafeContents DER. */
    private fun parseSafeContentsContent(
        parts: List<Der>,
        password: CharArray,
        certs: MutableList<X509Certificate>,
        onKey: (PrivateKey) -> Unit
    ) {
        val explicit = DerReader.children(parts[1].content).first()
        parseSafeBags(explicit.content, password, certs, onKey)
    }

    /** EncryptedData ContentInfo: version, EncryptedContentInfo{ oid, PBES2 alg, [0] IMPLICIT OCTET }. */
    private fun parseEncryptedData(
        parts: List<Der>,
        password: CharArray,
        certs: MutableList<X509Certificate>
    ) {
        // parts[0] = encryptedData OID; parts[1] = [0] EXPLICIT EncryptedData SEQ
        val explicit = DerReader.children(parts[1].content).first()
        val eciParts = DerReader.children(explicit.content)
        // eciParts = [version INT, EncryptedContentInfo SEQ]
        require(eciParts.size >= 2) { "EncryptedData malformed" }
        val eci = DerReader.children(eciParts[1].content)
        // eci = [contentType OID, contentEncryptionAlgorithm, [0] IMPLICIT OCTET STRING]
        require(eci.size >= 3) { "EncryptedContentInfo malformed" }
        val alg = eci[1]
        val ciphertext = eci[2].content // [0] IMPLICIT OCTET STRING -> raw bytes
        val safeContents = decryptPbes2(alg, ciphertext, password)
        // The decrypted SafeContents holds certificate bags.
        parseSafeBags(safeContents, password, certs) {}
    }

    /** Decrypt a PBES2-encrypted blob given its AlgorithmIdentifier. */
    private fun decryptPbes2(algorithm: Der, encrypted: ByteArray, password: CharArray): ByteArray {
        val algParts = DerReader.children(algorithm.content)
        require(algParts.size >= 2 && algParts[0].content.contentEquals(OID_PBES2)) { "not PBES2" }
        // PBES2-params ::= SEQUENCE { kdf AlgorithmIdentifier, enc AlgorithmIdentifier }
        val pbes2 = DerReader.children(algParts[1].content)
        val kdf = DerReader.children(pbes2[0].content)
        // KDF must be PBKDF2 (OpenSSL 3 also supports scrypt — not available on the
        // platform JCE, so report it by name instead of a generic failure).
        if (kdf.isEmpty() || !kdf[0].content.contentEquals(OID_PBKDF2)) {
            error(oidName(kdf.firstOrNull()?.content))
        }
        val kdfParams = DerReader.children(kdf[1].content)
        val salt = kdfParams[0].content
        val iterations = readInt(kdfParams[1].content)
        require(iterations in 1..10_000_000) { "implausible PBKDF2 iteration count" }
        // Optional PBKDF2-params elements: keyLength (INTEGER) and/or prf (SEQUENCE),
        // in either order. Distinguish by DER tag — mistaking prf for keyLength yields
        // an absurd key size and a PBKDF2 that runs for hours.
        var keyBits = -1 // -1 = not written; defaulted below from the cipher
        var prfName = "PBKDF2WithHmacSHA1"
        for (i in 2 until kdfParams.size) {
            val param = kdfParams[i]
            when (param.tag) {
                0x02 -> keyBits = readInt(param.content) * 8
                0x30 -> {
                    val prf = DerReader.children(param.content)
                    prfName = when {
                        prf.isEmpty() || prf[0].content.contentEquals(OID_HMAC_SHA1) -> "PBKDF2WithHmacSHA1"
                        prf[0].content.contentEquals(OID_HMAC_SHA256) -> "PBKDF2WithHmacSHA256"
                        prf[0].content.contentEquals(OID_HMAC_SHA384) -> "PBKDF2WithHmacSHA384"
                        prf[0].content.contentEquals(OID_HMAC_SHA512) -> "PBKDF2WithHmacSHA512"
                        else -> error(oidName(prf[0].content))
                    }
                }
            }
        }
        val enc = DerReader.children(pbes2[1].content)
        val encOid = enc[0].content
        val iv = enc[1].content
        val (cipherName, aesKeyBits) = when {
            encOid.contentEquals(OID_AES_256_CBC) -> "AES/CBC/PKCS5Padding" to 256
            encOid.contentEquals(OID_AES_192_CBC) -> "AES/CBC/PKCS5Padding" to 192
            encOid.contentEquals(OID_AES_128_CBC) -> "AES/CBC/PKCS5Padding" to 128
            encOid.contentEquals(OID_DES_EDE3_CBC) -> "DESede/CBC/PKCS5Padding" to 192
            else -> error(oidName(encOid))
        }
        val finalKeyBits = if (keyBits > 0) keyBits else aesKeyBits
        require(finalKeyBits in 128..512) { "implausible PBKDF2 key size" }

        val spec = PBEKeySpec(password, salt, iterations, finalKeyBits)
        val secretKey = try {
            SecretKeyFactory.getInstance(prfName).generateSecret(spec)
        } catch (e: java.security.NoSuchAlgorithmException) {
            // e.g. PBKDF2WithHmacSHA512 needs API 26+; surface the real reason.
            error(prfName.replace("PBKDF2WithHmac", "PBKDF2-HMAC-") + " (needs Android 8.0+)")
        }
        val cipher = Cipher.getInstance(cipherName)
        cipher.init(
            Cipher.DECRYPT_MODE,
            if (cipherName == "AES/CBC/PKCS5Padding") SecretKeySpec(secretKey.encoded, "AES")
            else SecretKeySpec(secretKey.encoded, "DESede"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(encrypted)
    }

    /** Human-readable name for a known algorithm OID. */
    private fun oidName(oid: ByteArray?): String = when {
        oid == null -> "unknown algorithm"
        oid.contentEquals(OID_PBKDF2) -> "PBKDF2"
        oid.contentEquals(OID_HMAC_SHA1) -> "PBKDF2-HMAC-SHA1"
        oid.contentEquals(OID_HMAC_SHA256) -> "PBKDF2-HMAC-SHA256"
        oid.contentEquals(OID_HMAC_SHA384) -> "PBKDF2-HMAC-SHA384"
        oid.contentEquals(OID_HMAC_SHA512) -> "PBKDF2-HMAC-SHA512"
        oid.contentEquals(OID_AES_256_CBC) -> "AES-256-CBC"
        oid.contentEquals(OID_AES_192_CBC) -> "AES-192-CBC"
        oid.contentEquals(OID_AES_128_CBC) -> "AES-128-CBC"
        oid.contentEquals(OID_DES_EDE3_CBC) -> "DES-EDE3-CBC"
        else -> "unknown algorithm"
    }

    private fun parseSafeBags(
        safeContentsDer: ByteArray,
        password: CharArray,
        certs: MutableList<X509Certificate>,
        onKey: (PrivateKey) -> Unit
    ) {
        val safeContents = DerReader.read(safeContentsDer)
        require(safeContents.tag == 0x30) { "SafeContents malformed" }
        for (bag in DerReader.children(safeContents.content)) {
            val bagParts = DerReader.children(bag.content)
            if (bagParts.size < 2) continue
            val bagOid = bagParts[0].content
            val bagValue = DerReader.children(bagParts[1].content).first()
            when {
                bagOid.contentEquals(OID_PKCS8_SHROUDED_KEY_BAG) ->
                    onKey(decryptShroudedKeyBag(bagValue, password))
                bagOid.contentEquals(OID_CERT_BAG) -> parseCertBag(bagValue, certs)
            }
        }
    }

    private fun decryptShroudedKeyBag(bagValue: Der, password: CharArray): PrivateKey {
        // bagValue = the SafeBag value SEQ (EncryptedPrivateKeyInfo): children are
        // [AlgorithmIdentifier, encryptedData OCTET STRING].
        val parts = DerReader.children(bagValue.content)
        require(parts.size == 2) { "EncryptedPrivateKeyInfo malformed" }
        val pkcs8 = decryptPbes2(parts[0], parts[1].content, password)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun parseCertBag(bagValue: Der, certs: MutableList<X509Certificate>) {
        // bagValue = the SafeBag value SEQ (CertBag): children are
        // [certType OID, [0] EXPLICIT OCTET STRING (full X.509 DER)].
        val parts = DerReader.children(bagValue.content)
        if (parts.size < 2) return
        if (!parts[0].content.contentEquals(OID_X509_CERT)) return
        // [0] EXPLICIT -> OCTET STRING -> full X.509 certificate DER.
        val explicit = DerReader.children(parts[1].content).first()
        val certDer = explicit.content
        val factory = CertificateFactory.getInstance("X.509")
        certs.add(factory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate)
    }

    private fun readInt(bytes: ByteArray): Int {
        var value = 0
        var start = 0
        if (bytes.size > 1 && bytes[0].toInt() == 0x00) start = 1 // strip leading zero for positive
        for (i in start until bytes.size) value = (value shl 8) or (bytes[i].toInt() and 0xff)
        return value
    }

    /** Minimal DER element parser. */
    private data class Der(val tag: Int, val content: ByteArray)

    private object DerReader {
        fun read(der: ByteArray, offset: Int = 0): Der {
            require(offset < der.size) { "DER truncated" }
            val tag = der[offset].toInt() and 0xff
            var i = offset + 1
            var len = der[i].toInt() and 0xff
            i++
            if (len and 0x80 != 0) {
                val numBytes = len and 0x7f
                len = 0
                repeat(numBytes) {
                    len = (len shl 8) or (der[i].toInt() and 0xff)
                    i++
                }
            }
            require(i + len <= der.size) { "DER length overflow" }
            return Der(tag, der.copyOfRange(i, i + len))
        }

        fun children(content: ByteArray): List<Der> {
            val out = mutableListOf<Der>()
            var off = 0
            while (off < content.size) {
                val d = read(content, off)
                out.add(d)
                val step = headerSize(content, off) + d.content.size
                if (step <= 0) break // defensive: never spin on malformed input
                off += step
            }
            return out
        }

        private fun headerSize(der: ByteArray, offset: Int): Int {
            var i = offset + 1
            var len = der[i].toInt() and 0xff
            i++
            if (len and 0x80 != 0) i += len and 0x7f
            return i - offset
        }
    }
}
