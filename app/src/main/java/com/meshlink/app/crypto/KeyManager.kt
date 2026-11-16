package com.meshlink.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Hardware-backed identity key stored in the Android Keystore.
 *
 * The private key never leaves the keystore. The key pair is generated for
 * device identity and future message signing; it is not transmitted.
 */
class KeyManager {

    companion object {
        const val KEY_ALIAS = "meshlink_identity_key"
    }

    fun generateE2EEKeyPair() {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .build()
        keyPairGenerator.initialize(parameterSpec)
        keyPairGenerator.generateKeyPair()
    }

    /** Creates the identity key once; safe to call on every launch. */
    fun ensureE2EEKeyPair() {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            generateE2EEKeyPair()
        }
    }
}
