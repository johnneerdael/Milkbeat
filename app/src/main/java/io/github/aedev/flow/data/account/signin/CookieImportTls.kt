package io.github.aedev.flow.data.account.signin

import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Ephemeral identity for one setup session. The fingerprint must be verified on the other device. */
internal class CookieImportTls(
    val store: KeyStore,
    val password: CharArray,
    val fingerprint: String,
) {
    companion object {
        const val ALIAS = "milkbeat-setup"

        fun create(host: String): CookieImportTls {
            val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val now = Instant.now()
            val subject = X500Principal("CN=Milkbeat sign-in")
            val certificate =
                JcaX509v3CertificateBuilder(
                    subject, BigInteger(159, SecureRandom()).setBit(158), Date.from(now.minus(5, ChronoUnit.MINUTES)),
                    Date.from(now.plus(1, ChronoUnit.DAYS)), subject, key.public,
                ).apply {
                    addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                    addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.iPAddress, host)))
                }.build(JcaContentSignerBuilder("SHA256withECDSA").build(key.private))
            val leaf = CertificateFactory.getInstance("X.509").generateCertificate(certificate.encoded.inputStream())
            val password = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.toCharArray()
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(ALIAS, key.private, password, arrayOf(leaf))
            }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString(":") { "%02X".format(it) }
            return CookieImportTls(store, password, fingerprint)
        }
    }
}
