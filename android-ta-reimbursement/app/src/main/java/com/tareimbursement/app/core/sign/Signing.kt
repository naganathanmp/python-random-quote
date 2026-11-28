package com.tareimbursement.app.core.sign

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/** Something that can sign bytes with an EC P-256 key (Android Keystore on the phone, a soft key in tests). */
interface DocSigner {
    val publicKey: PublicKey
    /** SHA256withECDSA signature of [data]. */
    fun sign(data: ByteArray): ByteArray
}

object Signing {
    /** BouncyCastle is used as an object (not registered), so it never clashes with Android's built-in "BC". */
    val bc = BouncyCastleProvider()
    private val algId: AlgorithmIdentifier = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA")

    private class Adapter(private val s: DocSigner) : ContentSigner {
        private val buf = ByteArrayOutputStream()
        override fun getAlgorithmIdentifier() = algId
        override fun getOutputStream(): OutputStream = buf
        override fun getSignature(): ByteArray = s.sign(buf.toByteArray())
    }

    /** Self-signed certificate naming the person: "Name (code) - signed with TA Reimburse". */
    fun selfSignedCert(signer: DocSigner, commonName: String, organisation: String, now: Instant = Instant.now()): X509Certificate {
        val cn = commonName.replace(Regex("[,=+<>#;\"\\\\]"), " ").trim().ifBlank { "TA Reimburse user" }
        val org = organisation.replace(Regex("[,=+<>#;\"\\\\]"), " ").trim()
        val name = X500Name("CN=$cn" + (if (org.isNotBlank()) ", O=$org" else "") + ", OU=Signed with TA Reimburse (self-issued)")
        val b = X509v3CertificateBuilder(
            name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minusSeconds(3600)), Date.from(now.plusSeconds(20L * 365 * 24 * 3600)),
            name, SubjectPublicKeyInfo.getInstance(signer.publicKey.encoded),
        )
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(b.build(Adapter(signer)))
    }

    /** Detached CMS (PKCS#7) signature over [data], with the certificate embedded. */
    fun cmsDetached(data: ByteArray, signer: DocSigner, cert: X509Certificate): ByteArray {
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(bc).build()).build(Adapter(signer), cert),
        )
        gen.addCertificate(X509CertificateHolder(cert.encoded))
        return gen.generate(CMSProcessableByteArray(data), false).getEncoded("DER")
    }

    data class Verification(
        val valid: Boolean,
        val signer: String?,
        val signedAt: LocalDateTime?,
        /** PDF only: the signature covers the whole file (nothing appended afterwards). */
        val coversWholeFile: Boolean = true,
        val message: String,
    )

    /** Verifies a detached CMS signature [p7s] over [data]. */
    fun verifyDetached(data: ByteArray, p7s: ByteArray): Verification = try {
        val cms = CMSSignedData(CMSProcessableByteArray(data), p7s)
        val si: SignerInformation = cms.signerInfos.signers.first()
        @Suppress("UNCHECKED_CAST")
        val holder = cms.certificates.getMatches(si.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).first()
        val ok = si.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(holder))
        val cn = holder.subject.getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN).firstOrNull()?.first?.value?.toString()
        val time = si.signedAttributes?.get(org.bouncycastle.asn1.cms.CMSAttributes.signingTime)?.attrValues?.getObjectAt(0)
            ?.let { org.bouncycastle.asn1.cms.Time.getInstance(it).date }?.let { LocalDateTime.ofInstant(it.toInstant(), ZoneId.systemDefault()) }
        Verification(ok, cn, time, message = if (ok) "Signature is valid - the file has not been changed since it was signed." else "Signature does NOT match - the file was changed after signing.")
    } catch (e: Exception) {
        if (System.getProperty("ta.debug") != null) e.printStackTrace()
        Verification(false, null, null, message = "Signature could not be checked: ${e.message ?: e.javaClass.simpleName}")
    }
}
