/*
 * Kontalk Java client
 * Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.crypto;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.Security;
import java.util.Iterator;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.operator.KeyFingerPrintCalculator;
import org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.kontalk.util.EncodingUtils;

/** Some PGP utility method, mainly for use by {@link PersonalKey}. */
public final class PGPUtils {
    private final static Logger LOGGER = Logger.getLogger(PGPUtils.class.getName());

    /** Security provider: Bouncy Castle. */
    public static final String PROVIDER = "BC";

    /** Singleton for converting a PGP key to a JCA key. */
    private static JcaPGPKeyConverter sKeyConverter;

    private PGPUtils() {
    }

    /**
     * A users public key for encryption and signing together with UID and
     * fingerprint (from signing key).
     */
    public static final class PGPCoderKey {
        final PGPPublicKey encryptKey;
        public final String userID;
        public final String fingerprint;

        public PGPCoderKey(PGPPublicKey encryptKey, String userID, String fingerprint) {
            this.encryptKey = encryptKey;
            this.userID = userID;
            this.fingerprint = fingerprint;
        }
    }

    public static void registerProvider() {
        // register bouncy castle provider
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /**
     * Read a public key from key ring data.
     */
    public static Optional<PGPCoderKey> readPublicKey(byte[] publicKeyring) {
        PGPPublicKeyRingCollection pgpPub;
        try {
            pgpPub = new PGPPublicKeyRingCollection(publicKeyring);
        } catch (IOException | PGPException ex) {
            LOGGER.log(Level.WARNING, "can't read public key ring", ex);
            return Optional.empty();
        }
        Iterator<?> keyRingIter = pgpPub.getKeyRings();
        if (!keyRingIter.hasNext()) {
            LOGGER.warning("no key ring in key ring collection");
            return Optional.empty();
        }
        PGPPublicKey encryptKey = null;
        String uid = null;
        String fp = null;
        PGPPublicKeyRing keyRing = (PGPPublicKeyRing) keyRingIter.next();
        Iterator<?> keyIter = keyRing.getPublicKeys();
        while (keyIter.hasNext()) {
            PGPPublicKey key = (PGPPublicKey) keyIter.next();
            if (key.isMasterKey()) {
                fp = EncodingUtils.bytesToHex(key.getFingerprint());
                Iterator<?> uidIt = key.getUserIDs();
                if (uidIt.hasNext())
                    uid = (String) uidIt.next();
            }
            if (!key.isMasterKey() && key.isEncryptionKey()) {
                encryptKey = key;
            }
        }
        if (encryptKey == null || uid == null || fp == null) {
            LOGGER.warning("can't find public keys in key ring");
            return Optional.empty();
        }
        return Optional.of(new PGPCoderKey(encryptKey, uid, fp));
    }

    private static void ensureKeyConverter() {
    	if (sKeyConverter == null)
    		sKeyConverter = new JcaPGPKeyConverter().setProvider(PGPUtils.PROVIDER);
    }

    static PrivateKey convertPrivateKey(PGPPrivateKey key) throws PGPException {
    	ensureKeyConverter();
    	return sKeyConverter.getPrivateKey(key);
    }

    public static PGPSecretKeyRing copySecretKeyRingWithNewPassword(byte[] privateKeyData,
            char[] oldPassphrase, char[] newPassphrase) throws PGPException, IOException {

        // load the secret key ring
        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);

        PGPDigestCalculatorProvider sha1CalcProv = new JcaPGPDigestCalculatorProviderBuilder().build();
        PBESecretKeyDecryptor decryptor = new JcePBESecretKeyDecryptorBuilder(sha1CalcProv)
            .setProvider(PGPUtils.PROVIDER)
            .build(oldPassphrase);

        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);
        PBESecretKeyEncryptor encryptor = new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
            .setProvider(PROVIDER).build(newPassphrase);

        return PGPSecretKeyRing.copyWithNewPassword(secRing, decryptor, encryptor);
    }
}
