package com.hyproj.blitzlink.lite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Host identity — {@code blitzlink_core::identity} on the JDK's Ed25519. The key file is the same one native BlitzLink
 * writes (the 32-byte seed as one line of 64 hex characters), so a server keeps its fingerprint and join code when it
 * switches between native and Lite.
 */
public final class Identity {
    public static final byte[] CONTEXT = "blitzlink/server-identity/v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] RELAY_CONTEXT = "blitzlink/relay-host/v1".getBytes(StandardCharsets.US_ASCII);
    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519; the raw 32-byte key follows. */
    private static final byte[] X509_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final PrivateKey priv;
    private final byte[] pub;

    private Identity(byte[] seed) {
        try {
            priv = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed.clone()));
            // The JDK derives the public key only while generating a pair: feed it the seed as its "randomness".
            KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
            g.initialize(NamedParameterSpec.ED25519, new FixedRandom(seed));
            KeyPair kp = g.generateKeyPair();
            byte[] enc = kp.getPublic().getEncoded();
            pub = Arrays.copyOfRange(enc, enc.length - 32, enc.length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    public static Identity fromSeed(byte[] seed) {
        if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
        return new Identity(seed);
    }

    public static Identity ephemeral() {
        return new Identity(Wire.random(32));
    }

    /** Reads the key file or creates it. {@code created[0]} is set when a new key was written. */
    public static Identity loadOrCreate(Path path, boolean[] created) throws IOException {
        if (Files.exists(path)) {
            byte[] seed = Wire.unhex(Files.readString(path, StandardCharsets.UTF_8).trim());
            if (seed == null || seed.length != 32) throw new IOException(path + ": not 64 hex characters");
            return new Identity(seed);
        }
        byte[] seed = Wire.random(32);
        Path dir = path.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.writeString(path, Wire.hex(seed) + "\n", StandardCharsets.UTF_8);
        if (created != null && created.length > 0) created[0] = true;
        return new Identity(seed);
    }

    public byte[] publicKey() {
        return pub.clone();
    }

    public String fingerprint() {
        return fingerprint(pub);
    }

    /** Signature for the relay registration (over {@code RELAY_CONTEXT ‖ nonce}). */
    public byte[] relaySig(byte[] nonce) {
        return sign(concat(RELAY_CONTEXT, nonce));
    }

    /** Proof for a joiner's {@code probe} (over {@code CONTEXT ‖ nonce}). */
    public byte[] proof(byte[] nonce) {
        return sign(concat(CONTEXT, nonce));
    }

    private byte[] sign(byte[] msg) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(msg);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether {@code sig} is the key's identity proof for {@code nonce}. */
    public static boolean verify(byte[] key, byte[] nonce, byte[] sig) {
        return verifyMessage(key, concat(CONTEXT, nonce), sig);
    }

    public static boolean verifyRelay(byte[] key, byte[] nonce, byte[] sig) {
        return verifyMessage(key, concat(RELAY_CONTEXT, nonce), sig);
    }

    private static boolean verifyMessage(byte[] key, byte[] msg, byte[] sig) {
        if (key == null || key.length != 32 || sig == null || sig.length != 64) return false;
        try {
            PublicKey pk = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, key)));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(msg);
            return s.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** The first 80 bits of SHA-256(key) in base32, {@code ABCD-EFGH-IJKL-MNOP}. */
    public static String fingerprint(byte[] key) {
        byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256").digest(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder out = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            int idx = 0;
            for (int b = 0; b < 5; b++) {
                int bit = i * 5 + b;
                idx = (idx << 1) | ((d[bit / 8] >> (7 - bit % 8)) & 1);
            }
            out.append(B32.charAt(idx));
        }
        return out.toString();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Hands out exactly the given bytes (once). */
    private static final class FixedRandom extends SecureRandom {
        private final byte[] bytes;

        FixedRandom(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(byte[] out) {
            System.arraycopy(bytes, 0, out, 0, Math.min(out.length, bytes.length));
        }
    }
}
