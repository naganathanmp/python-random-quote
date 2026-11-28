package com.tareimbursement.app.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tareimbursement.app.core.sign.DocSigner
import com.tareimbursement.app.core.sign.Signing
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/** EC P-256 signing key kept in the Android Keystore (the private key never leaves secure hardware). */
class KeystoreDocSigner(private val alias: String = "ta_doc_sign_v1") : DocSigner {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
    }

    override val publicKey: PublicKey get() = ks.getCertificate(alias).publicKey
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(ks.getKey(alias, null) as PrivateKey); update(data); sign()
    }
}

/**
 * The person's signing identity: a Keystore key plus a self-issued certificate naming them.
 * The certificate is re-issued (same key) when the profile name changes.
 */
class SigningIdentity(context: Context, private val signerFactory: () -> DocSigner = { KeystoreDocSigner() }) {
    private val sp = context.getSharedPreferences("ta_signing", Context.MODE_PRIVATE)
    private val signer: DocSigner by lazy { signerFactory() }

    /** (signer, certificate) or null if this phone can't create a signing key. */
    fun get(name: String, code: String, organisation: String): Pair<DocSigner, X509Certificate>? = try {
        val cn = if (code.isBlank()) name.trim() else "${name.trim()} ($code)"
        val key = "$cn|$organisation"
        val cached = if (sp.getString("subject", null) == key) sp.getString("cert", null)?.let { decode(it) } else null
        val cert = cached?.takeIf { it.publicKey == signer.publicKey } ?: Signing.selfSignedCert(signer, cn, organisation).also {
            sp.edit().putString("subject", key).putString("cert", Base64.encodeToString(it.encoded, Base64.NO_WRAP)).apply()
        }
        signer to cert
    } catch (e: Throwable) { null }

    private fun decode(b64: String): X509Certificate? = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(b64, Base64.NO_WRAP))) as X509Certificate
    } catch (e: Exception) { null }
}
