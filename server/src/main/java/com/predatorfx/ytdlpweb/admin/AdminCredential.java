package com.predatorfx.ytdlpweb.admin;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * The admin login, kept only as a salted PBKDF2-SHA256 hash of {@code username + "\n" + password}.
 *
 * The repo is public and even the local config should never hold the plaintext, so the owner
 * generates this string once (python one-liner in application.properties.example) and the server
 * only ever compares hashes. The exact bytes are hashed, so both parts are case-sensitive.
 *
 * Format: {@code pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>}
 */
public final class AdminCredential {

    private static final String SCHEME = "pbkdf2-sha256";
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final byte[] salt;
    private final byte[] hash;

    private AdminCredential(int iterations, byte[] salt, byte[] hash) {
        this.iterations = iterations;
        this.salt = salt;
        this.hash = hash;
    }

    /** Empty when blank or not a hash this class wrote — the admin login is then off. */
    public static Optional<AdminCredential> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        String[] parts = encoded.trim().split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] hash = Base64.getDecoder().decode(parts[3]);
            if (iterations < 1 || salt.length < 8 || hash.length != KEY_BITS / 8) {
                return Optional.empty();
            }
            return Optional.of(new AdminCredential(iterations, salt, hash));
        } catch (IllegalArgumentException e) { // bad number or bad base64
            return Optional.empty();
        }
    }

    public static String hash(String username, String password, byte[] salt, int iterations) {
        Base64.Encoder b64 = Base64.getEncoder();
        return SCHEME + "$" + iterations + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(derive(username, password, salt, iterations));
    }

    public boolean matches(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        return MessageDigest.isEqual(hash, derive(username, password, salt, iterations));
    }

    private static byte[] derive(String username, String password, byte[] salt, int iterations) {
        // The JDK's PBKDF2 encodes these chars as UTF-8, the same bytes python's
        // (u + "\n" + p).encode() produces — the documented hash command relies on that.
        char[] secret = (username + "\n" + password).toCharArray();
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
