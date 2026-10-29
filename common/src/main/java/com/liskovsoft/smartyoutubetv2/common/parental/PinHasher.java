package com.liskovsoft.smartyoutubetv2.common.parental;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Salted SHA-256 hashing for the parental-limit PIN.
 *
 * <p>Intentionally minimal — no PBKDF2/scrypt — because:
 * <ul>
 *   <li>The PIN is local-only; there's no network authentication surface.</li>
 *   <li>The threat is a kid reading a cleartext file, not brute force.</li>
 *   <li>Keeps zero new crypto dependencies.</li>
 * </ul>
 */
final class PinHasher {
    private PinHasher() {}

    static String hash(String pin, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] out = digest.digest(pin.getBytes("UTF-8"));
            return toHex(out);
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            // SHA-256 / UTF-8 are guaranteed by the JRE on Android.
            throw new RuntimeException(e);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    | Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}