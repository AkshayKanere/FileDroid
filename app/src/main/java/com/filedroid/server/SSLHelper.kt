package com.filedroid.server

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

object SSLHelper {

    private const val KEYSTORE_FILE = "filedroid.bks"
    private const val KEYSTORE_PASSWORD = "filedroid"
    private const val KEY_ALIAS = "filedroid"

    fun getSSLServerSocketFactory(context: Context): SSLServerSocketFactory? {
        return try {
            val keyStore = getOrCreateKeyStore(context)

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray())

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, SecureRandom())

            sslContext.serverSocketFactory
        } catch (e: Exception) {
            android.util.Log.e("SSLHelper", "Failed to create SSL context", e)
            null
        }
    }

    private fun getOrCreateKeyStore(context: Context): KeyStore {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())

        if (ksFile.exists()) {
            FileInputStream(ksFile).use { fis ->
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray())
            }
            return keyStore
        }

        // Generate new self-signed certificate
        keyStore.load(null, KEYSTORE_PASSWORD.toCharArray())

        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()

        // Create self-signed cert using reflection to avoid bouncycastle dependency
        val cert = generateSelfSignedCert(keyPair)

        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(cert)
        )

        FileOutputStream(ksFile).use { fos ->
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }

        return keyStore
    }

    private fun generateSelfSignedCert(keyPair: KeyPair): X509Certificate {
        // Use Android's built-in X509 certificate generation
        val startDate = Date()
        val endDate = Calendar.getInstance().apply {
            add(Calendar.YEAR, 10)
        }.time

        // Using the android.security approach for self-signed certs
        val subject = "CN=FileDroid, O=FileDroid, L=Local"

        // Use sun.security alternative available on Android
        try {
            val certInfoClass = Class.forName("sun.security.x509.X509CertInfo")
            val certInfo = certInfoClass.getDeclaredConstructor().newInstance()

            val x500Name = Class.forName("sun.security.x509.X500Name")
                .getDeclaredConstructor(String::class.java)
                .newInstance(subject)

            // Set validity
            val certValidity = Class.forName("sun.security.x509.CertificateValidity")
                .getDeclaredConstructor(Date::class.java, Date::class.java)
                .newInstance(startDate, endDate)

            val serialNumber = Class.forName("sun.security.x509.CertificateSerialNumber")
                .getDeclaredConstructor(Int::class.java)
                .newInstance((Math.random() * Int.MAX_VALUE).toInt())

            val setMethod = certInfoClass.getMethod("set", String::class.java, Any::class.java)
            setMethod.invoke(certInfo, "validity", certValidity)
            setMethod.invoke(certInfo, "serialNumber", serialNumber)
            setMethod.invoke(certInfo, "subject", x500Name)
            setMethod.invoke(certInfo, "issuer", x500Name)

            val certKeyClass = Class.forName("sun.security.x509.CertificateX509Key")
            val certKey = certKeyClass.getDeclaredConstructor(PublicKey::class.java)
                .newInstance(keyPair.public)
            setMethod.invoke(certInfo, "key", certKey)

            // Version
            val certVersion = Class.forName("sun.security.x509.CertificateVersion")
                .getDeclaredConstructor(Int::class.java)
                .newInstance(2) // V3

            setMethod.invoke(certInfo, "version", certVersion)

            // Algorithm
            val algId = Class.forName("sun.security.x509.AlgorithmId")
                .getMethod("get", String::class.java)
                .invoke(null, "SHA256withRSA")

            val certAlg = Class.forName("sun.security.x509.CertificateAlgorithmId")
                .getDeclaredConstructor(Class.forName("sun.security.x509.AlgorithmId"))
                .newInstance(algId)
            setMethod.invoke(certInfo, "algorithmID", certAlg)

            // Create cert
            val x509CertImpl = Class.forName("sun.security.x509.X509CertImpl")
                .getDeclaredConstructor(certInfoClass)
                .newInstance(certInfo)

            x509CertImpl.javaClass.getMethod("sign", PrivateKey::class.java, String::class.java)
                .invoke(x509CertImpl, keyPair.private, "SHA256withRSA")

            return x509CertImpl as X509Certificate
        } catch (e: Exception) {
            // Fallback: simple self-signed cert approach
            throw RuntimeException("Failed to generate self-signed certificate. " +
                    "HTTPS will not be available.", e)
        }
    }
}
