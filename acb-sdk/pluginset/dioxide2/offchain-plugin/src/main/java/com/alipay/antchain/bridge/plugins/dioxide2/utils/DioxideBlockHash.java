package com.alipay.antchain.bridge.plugins.dioxide2.utils;

/** Converts a native Crockford Base32 block digest to its original 32 bytes. */
public final class DioxideBlockHash {
    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

    private DioxideBlockHash() {}

    public static byte[] decode(String value) {
        if (value == null || value.length() != 52) {
            throw new IllegalArgumentException("Dioxide block digest must contain 52 Base32 characters");
        }
        byte[] digest = new byte[32];
        int pending = 0;
        int bits = 0;
        int offset = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = ALPHABET.indexOf(value.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid Dioxide Base32 block digest");
            }
            pending = (pending << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                digest[offset++] = (byte) (pending >>> bits);
                pending &= (1 << bits) - 1;
            }
        }
        if (offset != digest.length || pending != 0) {
            throw new IllegalArgumentException("non-canonical Dioxide Base32 block digest padding");
        }
        return digest;
    }
}
