package dev.bill.openclone

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * and reuses it for every clone.
 *
 * Because that single key carries every clone the user ever made, it can be
 * exported to a file the user picks (SAF) and imported back on another
 * install or after a reinstall. The exported file is protected by a password
 * the user chooses; the internal storage password never leaves the device.
 */
class KeystoreManager(private val context: Context) {

    data class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate)

    /** Human-readable summary of the stored key, for the settings screen. */
    data class KeyInfo(
        val subject: String,
        val algorithm: String,
        val keySize: Int,
        val validFrom: Long,
        val validUntil: Long,
        val sha256Fingerprint: String,
    )

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
        val password = internalPassword()
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

    /**
     * Copy the signing key into [out], protected by [userPassword].
     *
     * PKCS12 stores the key entry and the store under the same password on
     * the JDK/Android provider, so the entry is re-keyed with the user's
     * password before the store is written. The on-device keystore keeps
     * using its own random password and is not modified.
     */
    @Synchronized
    fun exportTo(out: OutputStream, userPassword: CharArray) {
        requirePassword(userPassword)
        // Make sure a key exists before we try to export one.
        val current = getOrCreateKey()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, userPassword)
        ks.setKeyEntry(ALIAS, current.privateKey, userPassword, arrayOf<Certificate>(current.certificate))
        ks.store(out, userPassword)
    }

    /**
     * Replace the on-device signing key with the one in [input].
     *
     * The imported file may have been exported by OpenClone (alias
     * [ALIAS]) or by any other PKCS12 tool, so the first private-key entry
     * is used. The store is re-written under a fresh internal password so
     * the backup password is never kept on the device.
     */
    @Synchronized
    fun importFrom(input: InputStream, userPassword: CharArray) {
        requirePassword(userPassword)
        val source = KeyStore.getInstance("PKCS12")
        try {
            source.load(input, userPassword)
        } catch (e: Exception) {
            throw IllegalArgumentException("INVALID_PASSWORD_OR_FILE", e)
        }

        val alias = source.aliases().toList().firstOrNull { source.isKeyEntry(it) }
            ?: throw IllegalArgumentException("NO_PRIVATE_KEY")

        val key = (source.getKey(alias, userPassword) as? PrivateKey)
            ?: (source.getKey(alias, null) as? PrivateKey)
            ?: throw IllegalArgumentException("NO_PRIVATE_KEY")
        val chain = source.getCertificateChain(alias)
            ?: source.getCertificate(alias)?.let { arrayOf(it) }
            ?: throw IllegalArgumentException("NO_CERTIFICATE")

        val internal = internalPassword()
        val target = KeyStore.getInstance("PKCS12")
        target.load(null, internal.toCharArray())
        target.setKeyEntry(ALIAS, key, internal.toCharArray(), chain)

        // Write to a temp file first so a failed write cannot destroy the
        // key that is currently signing clones.
        val temp = File(context.filesDir, "openclone-keystore.p12.import")
        FileOutputStream(temp).use { target.store(it, internal.toCharArray()) }
        if (!temp.renameTo(keystoreFile)) {
            temp.copyTo(keystoreFile, overwrite = true)
            temp.delete()
        }
    }

    /** Details of the stored key, or null when no key has been created yet. */
    fun describeKey(): KeyInfo? {
        if (!keystoreFile.exists()) return null
        return try {
            val key = getOrCreateKey()
            val cert = key.certificate
            KeyInfo(
                subject = cert.subjectX500Principal.name,
                algorithm = key.privateKey.algorithm,
                keySize = keySizeOf(cert),
                validFrom = cert.notBefore.time,
                validUntil = cert.notAfter.time,
                sha256Fingerprint = fingerprintOf(cert),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Suggested file name for an export, e.g. openclone-key-20261125.p12. */
    fun suggestedExportName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "openclone-key-$stamp.p12"
    }

    private fun keySizeOf(cert: X509Certificate): Int = when (val publicKey = cert.publicKey) {
        is RSAPublicKey -> publicKey.modulus.bitLength()
        is ECPublicKey -> publicKey.params.order.bitLength()
        else -> 0
    }

    private fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** PKCS12 requires at least six characters for the store password. */
    private fun requirePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "PASSWORD_TOO_SHORT" }
    }

    private fun internalPassword(): String =
        prefs.getString(PREF_PASSWORD, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(PREF_PASSWORD, it).apply()
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
        const val MIN_PASSWORD_LENGTH = 6
        private const val ALIAS = "openclone"
        private const val PREF_PASSWORD = "keystore_password"
    }
}
