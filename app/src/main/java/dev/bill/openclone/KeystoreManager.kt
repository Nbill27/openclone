package dev.bill.openclone

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * The clone signing key.
 *
 * This is the part CloneHI got wrong in the comments: if the key is lost,
 * a clone can never be updated in place, it has to be uninstalled and
 * reinstalled, losing its data. So OpenClone generates ONE key the first
 * time it runs, keeps it in a PKCS12 keystore in private app storage,
 * and reuses it for every clone. The keystore file itself can be exported
 * from the history screen, back it up somewhere safe.
 */
class KeystoreManager(private val context: Context) {

    data class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate)

    private val keystoreFile: File
        get() = File(context.filesDir, "openclone-keystore.p12")

    private val prefs get() = context.getSharedPreferences("openclone-key", Context.MODE_PRIVATE)

    fun keystoreExists(): Boolean = keystoreFile.exists()

    fun keystoreFile(): File = keystoreFile

    /**
     * Load the existing key, or generate and store a new one.
     * The keystore password is random, stored only in private SharedPreferences.
     */
    @Synchronized
    fun getOrCreateKey(): SigningKey {
        val password = prefs.getString("keystore_password", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("keystore_password", it).apply()
            }
        if (keystoreFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(keystoreFile).use { ks.load(it, password.toCharArray()) }
                val key = ks.getKey(ALIAS, password.toCharArray()) as PrivateKey
                val cert = ks.getCertificate(ALIAS) as X509Certificate
                return SigningKey(key, cert)
            } catch (_: Exception) {
                // Corrupt keystore: fall through and generate a fresh one.
                // Clones signed with the old key can no longer be updated,
                // only reinstalled. That is unavoidable once the key is gone.
            }
        }
        val keyPair = generateKeyPair()
        val cert = selfSignedCertificate(keyPair)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password.toCharArray())
        ks.setKeyEntry(ALIAS, keyPair.private, password.toCharArray(), arrayOf<Certificate>(cert))
        FileOutputStream(keystoreFile).use { ks.store(it, password.toCharArray()) }
        return SigningKey(keyPair.private, cert)
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, SecureRandom())
        return gen.generateKeyPair()
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.YEAR, 25)
        val notAfter = calendar.time
        val issuer = X500Name("CN=OpenClone, O=OpenClone")
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(64, SecureRandom()),
            now,
            notAfter,
            issuer,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    companion object {
        private const val ALIAS = "openclone"
    }
}
